using System;
using System.Collections.Generic;
using System.IO;
using System.IO.Compression;
using System.Linq;

namespace MarkPdf.Core.Pdf
{
    /// <summary>
    /// 既存 PDF の読み取り（相互参照表・オブジェクト・ページツリー）。
    /// 相互参照は従来の xref 表と xref ストリーム（PDF 1.5+、オブジェクトストリーム）の両方に対応する。
    /// </summary>
    public sealed class PdfFile
    {
        private abstract class Entry { }

        private sealed class InFile : Entry
        {
            public InFile(int offset) { Offset = offset; }
            public int Offset { get; }
        }

        private sealed class InObjStm : Entry
        {
            public InObjStm(int stm, int index) { Stm = stm; Index = index; }
            public int Stm { get; }
            public int Index { get; }
        }

        private readonly byte[] _buf;
        private readonly Dictionary<int, Entry> _entries = new Dictionary<int, Entry>();
        private readonly Dictionary<int, (byte[] data, int[] offsets)> _objStmCache = new Dictionary<int, (byte[], int[])>();

        public PdfFile(byte[] buf)
        {
            _buf = buf;
            LastXrefOffset = FindStartXref();
            PdfDict? first = null;
            var firstIsStream = false;
            var visited = new HashSet<int>();
            int? off = LastXrefOffset;
            while (off.HasValue && visited.Add(off.Value))
            {
                var (t, isStream) = ReadXrefSection(off.Value);
                if (first == null)
                {
                    first = t;
                    firstIsStream = isStream;
                }
                // ハイブリッド形式：xref 表に加えて xref ストリームがある
                if (t["XRefStm"] is PdfNum xs && visited.Add(xs.Int)) ReadXrefSection(xs.Int);
                off = (t["Prev"] as PdfNum)?.Int;
            }
            Trailer = first ?? throw new PdfFormatException("trailer がない");
            UsesXrefStream = firstIsStream;
        }

        /// <summary>最新の trailer（xref ストリームの場合はその辞書）</summary>
        public PdfDict Trailer { get; }

        /// <summary>最新の相互参照の位置（増分保存の /Prev に使う）</summary>
        public int LastXrefOffset { get; }

        /// <summary>最新の相互参照が xref ストリームかどうか</summary>
        public bool UsesXrefStream { get; }

        public int Size => _buf.Length;

        private int FindStartXref()
        {
            var kw = "startxref";
            var from = Math.Max(0, Size - 2048);
            for (var i = Size - kw.Length; i >= from; i--)
            {
                var ok = true;
                for (var j = 0; j < kw.Length; j++)
                {
                    if (_buf[i + j] != kw[j])
                    {
                        ok = false;
                        break;
                    }
                }
                if (ok) return new PdfParser(_buf, i + kw.Length).ReadInt();
            }
            throw new PdfFormatException("startxref が見つからない");
        }

        /// <summary>1 つの相互参照セクションを読む。既存の項目は上書きしない（新しいものが優先）。</summary>
        private (PdfDict trailer, bool isStream) ReadXrefSection(int offset)
        {
            if (offset < 0 || offset >= Size) throw new PdfFormatException($"xref の位置が不正: {offset}");
            var p = new PdfParser(_buf, offset);
            if (p.Keyword("xref"))
            {
                while (true)
                {
                    if (p.Keyword("trailer"))
                    {
                        if (!(p.ParseObject() is PdfDict t)) throw new PdfFormatException("trailer が辞書でない");
                        return (t, false);
                    }
                    var start = p.ReadInt();
                    var count = p.ReadInt();
                    for (var i = 0; i < count; i++)
                    {
                        var off = p.ReadInt();
                        p.ReadInt(); // gen
                        p.SkipWs();
                        var type = p.Token();
                        if (type == "n" && off > 0 && !_entries.ContainsKey(start + i)) _entries[start + i] = new InFile(off);
                    }
                }
            }
            if (!(ParseIndirectAt(offset) is PdfStream s)) throw new PdfFormatException("xref ストリームでない");
            ReadXrefStream(s);
            return (s.Dict, true);
        }

        private void ReadXrefStream(PdfStream s)
        {
            var d = s.Dict;
            var data = Decode(s);
            var w = (d["W"] as PdfArray)?.Items.Select(x => ((PdfNum)x).Int).ToArray()
                    ?? throw new PdfFormatException("/W がない");
            var index = (d["Index"] as PdfArray)?.Items.Select(x => ((PdfNum)x).Int).ToArray()
                        ?? new[] { 0, ((PdfNum)d["Size"]!).Int };
            var rowLen = w.Sum();
            var pos = 0;

            long Field(int width, long def)
            {
                if (width == 0) return def;
                long v = 0;
                for (var k = 0; k < width; k++) v = (v << 8) | data[pos++];
                return v;
            }

            for (var i = 0; i + 1 < index.Length; i += 2)
            {
                var start = index[i];
                var count = index[i + 1];
                for (var k = 0; k < count; k++)
                {
                    if (pos + rowLen > data.Length) return;
                    var type = Field(w[0], 1);
                    var f2 = Field(w[1], 0);
                    var f3 = Field(w.Length > 2 ? w[2] : 0, 0);
                    if (_entries.ContainsKey(start + k)) continue;
                    if (type == 1) _entries[start + k] = new InFile((int)f2);
                    else if (type == 2) _entries[start + k] = new InObjStm((int)f2, (int)f3);
                }
            }
        }

        /// <summary>"n g obj ... endobj" を読む。</summary>
        private PdfObj ParseIndirectAt(int offset)
        {
            var p = new PdfParser(_buf, offset);
            p.ReadInt();
            p.ReadInt();
            if (!p.Keyword("obj")) throw new PdfFormatException($"obj がない @{offset}");
            var o = p.ParseObject();
            if (o is PdfDict dict && p.Keyword("stream"))
            {
                // "stream" の直後は CRLF か LF
                if (p.Peek() == '\r') p.Pos++;
                if (p.Peek() == '\n') p.Pos++;
                var start = p.Pos;
                var declared = (Resolve(dict["Length"]) as PdfNum)?.Int ?? -1;
                var len = declared >= 0 && EndstreamAt(start + declared) ? declared : SearchEndstream(start);
                return new PdfStream(dict, start, len);
            }
            return o;
        }

        private bool EndstreamAt(int pos) => pos <= Size && new PdfParser(_buf, pos).Keyword("endstream");

        private int SearchEndstream(int start)
        {
            var kw = "endstream";
            for (var i = start; i + kw.Length <= Size; i++)
            {
                var ok = true;
                for (var j = 0; j < kw.Length; j++)
                {
                    if (_buf[i + j] != kw[j])
                    {
                        ok = false;
                        break;
                    }
                }
                if (!ok) continue;
                var end = i;
                if (end > start && _buf[end - 1] == '\n') end--;
                if (end > start && _buf[end - 1] == '\r') end--;
                return end - start;
            }
            throw new PdfFormatException("endstream がない");
        }

        public PdfObj? GetObject(int num)
        {
            if (!_entries.TryGetValue(num, out var e)) return null;
            if (e is InFile f) return ParseIndirectAt(f.Offset);
            var s = (InObjStm)e;
            if (!_objStmCache.TryGetValue(s.Stm, out var stm)) _objStmCache[s.Stm] = stm = LoadObjStm(s.Stm);
            if (s.Index < 0 || s.Index >= stm.offsets.Length) return null;
            return new PdfParser(stm.data, stm.offsets[s.Index]).ParseObject();
        }

        private (byte[], int[]) LoadObjStm(int num)
        {
            if (!(_entries.TryGetValue(num, out var e) && e is InFile f && ParseIndirectAt(f.Offset) is PdfStream s))
                throw new PdfFormatException($"オブジェクトストリーム {num} が読めない");
            var data = Decode(s);
            var n = ((PdfNum)s.Dict["N"]!).Int;
            var first = ((PdfNum)s.Dict["First"]!).Int;
            var p = new PdfParser(data);
            var offsets = new int[n];
            for (var i = 0; i < n; i++)
            {
                p.ReadInt();
                offsets[i] = first + p.ReadInt();
            }
            return (data, offsets);
        }

        public PdfObj? Resolve(PdfObj? o) => o is PdfRef r ? GetObject(r.Num) : o;

        // ---- ストリームの展開（FlateDecode + PNG 予測子） ----

        private byte[] Decode(PdfStream s)
        {
            var raw = new byte[s.DataLength];
            Buffer.BlockCopy(_buf, s.DataStart, raw, 0, s.DataLength);
            List<string> filters;
            switch (Resolve(s.Dict["Filter"]))
            {
                case null: filters = new List<string>(); break;
                case PdfName n: filters = new List<string> { n.Raw }; break;
                case PdfArray a: filters = a.Items.Select(x => ((PdfName)x).Raw).ToList(); break;
                default: throw new PdfFormatException("不明なフィルタ");
            }
            List<PdfDict?> parms;
            switch (Resolve(s.Dict["DecodeParms"]))
            {
                case PdfDict d: parms = new List<PdfDict?> { d }; break;
                case PdfArray a: parms = a.Items.Select(x => Resolve(x) as PdfDict).ToList(); break;
                default: parms = new List<PdfDict?>(); break;
            }
            var data = raw;
            for (var i = 0; i < filters.Count; i++)
            {
                if (filters[i] != "FlateDecode" && filters[i] != "Fl") throw new PdfFormatException($"未対応のフィルタ: {filters[i]}");
                data = Unpredict(Inflate(data), i < parms.Count ? parms[i] : null);
            }
            return data;
        }

        private static byte[] Inflate(byte[] data)
        {
            // zlib ヘッダ（2 バイト）を飛ばして raw deflate として読む
            var start = data.Length >= 2 && (data[0] & 0x0F) == 8 && ((data[0] << 8) | data[1]) % 31 == 0 ? 2 : 0;
            using (var input = new MemoryStream(data, start, data.Length - start))
            using (var z = new DeflateStream(input, CompressionMode.Decompress))
            using (var o = new MemoryStream(data.Length * 4))
            {
                try
                {
                    z.CopyTo(o);
                }
                catch (InvalidDataException)
                {
                    // 末尾が壊れていても読めた分は使う
                }
                return o.ToArray();
            }
        }

        private static byte[] Unpredict(byte[] data, PdfDict? parms)
        {
            var predictor = (parms?["Predictor"] as PdfNum)?.Int ?? 1;
            if (predictor < 10)
            {
                if (predictor == 1) return data;
                throw new PdfFormatException($"未対応の予測子: {predictor}");
            }
            var colors = (parms?["Colors"] as PdfNum)?.Int ?? 1;
            var bpc = (parms?["BitsPerComponent"] as PdfNum)?.Int ?? 8;
            var columns = (parms?["Columns"] as PdfNum)?.Int ?? 1;
            var bpp = Math.Max(1, colors * bpc / 8);
            var rowLen = (colors * bpc * columns + 7) / 8;
            var o = new MemoryStream();
            var prev = new byte[rowLen];
            var row = new byte[rowLen];
            var pos = 0;
            while (pos + 1 + rowLen <= data.Length)
            {
                var type = data[pos++];
                for (var i = 0; i < rowLen; i++)
                {
                    int x = data[pos + i];
                    int a = i >= bpp ? row[i - bpp] : 0;
                    int b = prev[i];
                    int c = i >= bpp ? prev[i - bpp] : 0;
                    int v;
                    switch (type)
                    {
                        case 0: v = x; break;
                        case 1: v = x + a; break;
                        case 2: v = x + b; break;
                        case 3: v = x + (a + b) / 2; break;
                        case 4:
                            var pa = Math.Abs(b - c);
                            var pb = Math.Abs(a - c);
                            var pc = Math.Abs(a + b - 2 * c);
                            v = x + (pa <= pb && pa <= pc ? a : pb <= pc ? b : c);
                            break;
                        default: throw new PdfFormatException($"不正な PNG 予測子: {type}");
                    }
                    row[i] = (byte)v;
                }
                pos += rowLen;
                o.Write(row, 0, rowLen);
                Buffer.BlockCopy(row, 0, prev, 0, rowLen);
            }
            return o.ToArray();
        }

        // ---- ページ ----

        public sealed class Page
        {
            public Page(PdfRef reference, PdfDict dict, double[] box, int rotate)
            {
                Ref = reference;
                Dict = dict;
                Box = box;
                Rotate = rotate;
            }

            public PdfRef Ref { get; }
            public PdfDict Dict { get; }

            /// <summary>表示に使われる枠（CropBox、なければ MediaBox）[llx, lly, urx, ury]</summary>
            public double[] Box { get; }

            /// <summary>0 / 90 / 180 / 270</summary>
            public int Rotate { get; }
        }

        public List<Page> Pages()
        {
            if (!(Resolve(Trailer["Root"]) is PdfDict root)) throw new PdfFormatException("/Root がない");
            if (!(root["Pages"] is PdfRef pagesRef)) throw new PdfFormatException("/Pages がない");
            var result = new List<Page>();
            var visited = new HashSet<int>();

            void Walk(PdfRef r, PdfObj? media, PdfObj? crop, PdfObj? rotate)
            {
                if (!visited.Add(r.Num)) return;
                if (!(GetObject(r.Num) is PdfDict node)) return;
                var m = node["MediaBox"] ?? media;
                var c = node["CropBox"] ?? crop;
                var rot = node["Rotate"] ?? rotate;
                var kids = Resolve(node["Kids"]) as PdfArray;
                if (kids != null && (node["Type"] as PdfName)?.Raw != "Page")
                {
                    foreach (var k in kids.Items) if (k is PdfRef kr) Walk(kr, m, c, rot);
                }
                else
                {
                    var box = ToBox(Resolve(c)) ?? ToBox(Resolve(m)) ?? new[] { 0.0, 0.0, 612.0, 792.0 };
                    var deg = (((Resolve(rot) as PdfNum)?.Int ?? 0) % 360 + 360) % 360;
                    result.Add(new Page(r, node, box, deg));
                }
            }

            Walk(pagesRef, null, null, null);
            return result;
        }

        private double[]? ToBox(PdfObj? o)
        {
            if (!(o is PdfArray a) || a.Items.Count != 4) return null;
            var v = new double[4];
            for (var i = 0; i < 4; i++)
            {
                if (!(Resolve(a.Items[i]) is PdfNum n)) return null;
                v[i] = n.Double;
            }
            return new[] { Math.Min(v[0], v[2]), Math.Min(v[1], v[3]), Math.Max(v[0], v[2]), Math.Max(v[1], v[3]) };
        }
    }
}
