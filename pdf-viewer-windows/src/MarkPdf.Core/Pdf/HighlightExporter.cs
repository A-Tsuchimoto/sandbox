using System;
using System.Collections.Generic;
using System.Globalization;
using System.IO;
using System.Linq;
using System.Text;

namespace MarkPdf.Core.Pdf
{
    /// <summary>
    /// ハイライトを PDF 標準の「ハイライト注釈」として書き出す（Android 版と同じ方式）。
    /// 元の PDF は 1 バイトも変えず、末尾に追記する（増分保存）。
    /// </summary>
    public static class HighlightExporter
    {
        public enum Reason { Encrypted, PageMismatch, Broken }

        public sealed class UnsupportedPdfException : Exception
        {
            public UnsupportedPdfException(Reason reason) : base(reason.ToString()) { Reason = reason; }
            public Reason Reason { get; }
        }

        /// <summary>
        /// <paramref name="src"/>（元 PDF 全体）に <paramref name="highlights"/> を追記した PDF を <paramref name="output"/> に書く。
        /// </summary>
        /// <param name="expectedPages">表示中の PDF のページ数（読み取り結果の検証用）</param>
        public static void Export(byte[] src, Stream output, IDictionary<int, IReadOnlyList<Highlight>> highlights, int expectedPages)
        {
            PdfFile pdf;
            List<PdfFile.Page> pages;
            try
            {
                pdf = new PdfFile(src);
                if (pdf.Trailer["Encrypt"] != null) throw new UnsupportedPdfException(Reason.Encrypted);
                pages = pdf.Pages();
            }
            catch (PdfFormatException)
            {
                throw new UnsupportedPdfException(Reason.Broken);
            }
            catch (InvalidCastException)
            {
                throw new UnsupportedPdfException(Reason.Broken);
            }
            catch (IndexOutOfRangeException)
            {
                throw new UnsupportedPdfException(Reason.Broken);
            }
            if (pages.Count != expectedPages) throw new UnsupportedPdfException(Reason.PageMismatch);

            if (!(pdf.Trailer["Size"] is PdfNum size)) throw new UnsupportedPdfException(Reason.Broken);
            var nextNum = size.Int;
            var objects = new List<(PdfRef reference, byte[] body)>();

            foreach (var kv in highlights.OrderBy(k => k.Key))
            {
                if (kv.Value.Count == 0 || kv.Key < 0 || kv.Key >= pages.Count) continue;
                var page = pages[kv.Key];
                var newAnnots = new List<PdfObj>();
                foreach (var h in kv.Value)
                {
                    var apRef = new PdfRef(nextNum++, 0);
                    var annotRef = new PdfRef(nextNum++, 0);
                    var (annot, ap) = BuildAnnotation(h, page, annotRef);
                    objects.Add((apRef, ap));
                    var apDict = new PdfDict();
                    apDict["N"] = apRef;
                    annot["AP"] = apDict;
                    objects.Add((annotRef, PdfWriter.ToBytes(annot)));
                    newAnnots.Add(annotRef);
                }
                // ページ辞書をコピーして /Annots に追加し、同じ番号で上書き定義する
                var dict = new PdfDict(page.Dict);
                var existing = (pdf.Resolve(dict["Annots"]) as PdfArray)?.Items ?? new List<PdfObj>();
                dict["Annots"] = new PdfArray(existing.Concat(newAnnots));
                objects.Add((page.Ref, PdfWriter.ToBytes(dict)));
            }

            // ---- 書き出し ----
            output.Write(src, 0, src.Length);
            var tail = new MemoryStream();
            long offset = src.Length;
            if (src.Length == 0 || src[src.Length - 1] != '\n') tail.WriteByte((byte)'\n');
            var offsets = new Dictionary<int, long>();
            var gens = new Dictionary<int, int>();
            foreach (var (r, body) in objects)
            {
                offsets[r.Num] = offset + tail.Length;
                gens[r.Num] = r.Gen;
                PdfWriter.Ascii(tail, $"{r.Num} {r.Gen} obj\n");
                tail.Write(body, 0, body.Length);
                PdfWriter.Ascii(tail, "\nendobj\n");
            }

            var trailer = new PdfDict();
            if (pdf.Trailer["Root"] is PdfObj root) trailer["Root"] = root;
            if (pdf.Trailer["Info"] is PdfObj info) trailer["Info"] = info;
            if (pdf.Trailer["ID"] is PdfObj id) trailer["ID"] = id;
            trailer["Prev"] = new PdfNum(pdf.LastXrefOffset.ToString(CultureInfo.InvariantCulture));

            var xrefOffset = offset + tail.Length;
            if (pdf.UsesXrefStream)
            {
                // 元が xref ストリームなら、追記分も xref ストリームで書く（無圧縮）
                var xrefNum = nextNum++;
                offsets[xrefNum] = xrefOffset;
                var nums = offsets.Keys.OrderBy(n => n).ToList();
                var rows = new MemoryStream();
                foreach (var n in nums)
                {
                    var off = offsets[n];
                    rows.WriteByte(1);
                    for (var s = 24; s >= 0; s -= 8) rows.WriteByte((byte)((off >> s) & 0xFF));
                    gens.TryGetValue(n, out var g);
                    rows.WriteByte((byte)((g >> 8) & 0xFF));
                    rows.WriteByte((byte)(g & 0xFF));
                }
                trailer["Type"] = new PdfName("XRef");
                trailer["Size"] = Num(nextNum);
                trailer["W"] = new PdfArray(new PdfObj[] { Num(1), Num(4), Num(2) });
                trailer["Index"] = new PdfArray(Runs(nums).SelectMany(r => new PdfObj[] { Num(r.start), Num(r.count) }));
                trailer["Length"] = Num((int)rows.Length);
                PdfWriter.Ascii(tail, $"{xrefNum} 0 obj\n");
                PdfWriter.Write(trailer, tail);
                PdfWriter.Ascii(tail, "\nstream\n");
                rows.WriteTo(tail);
                PdfWriter.Ascii(tail, "\nendstream\nendobj\n");
            }
            else
            {
                var nums = offsets.Keys.OrderBy(n => n).ToList();
                PdfWriter.Ascii(tail, "xref\n");
                foreach (var (start, count) in Runs(nums))
                {
                    PdfWriter.Ascii(tail, $"{start} {count}\n");
                    for (var n = start; n < start + count; n++)
                    {
                        gens.TryGetValue(n, out var g);
                        PdfWriter.Ascii(tail, string.Format(CultureInfo.InvariantCulture, "{0:D10} {1:D5} n\r\n", offsets[n], g));
                    }
                }
                trailer["Size"] = Num(nextNum);
                PdfWriter.Ascii(tail, "trailer\n");
                PdfWriter.Write(trailer, tail);
                PdfWriter.Ascii(tail, "\n");
            }
            PdfWriter.Ascii(tail, $"startxref\n{xrefOffset}\n%%EOF\n");
            tail.WriteTo(output);
            output.Flush();
        }

        private static PdfNum Num(int v) => new PdfNum(v.ToString(CultureInfo.InvariantCulture));

        /// <summary>昇順の番号列を連続区間 (開始, 個数) に分ける</summary>
        private static List<(int start, int count)> Runs(List<int> sorted)
        {
            var result = new List<(int, int)>();
            int start = -1, count = 0;
            foreach (var n in sorted)
            {
                if (start >= 0 && n == start + count) count++;
                else
                {
                    if (start >= 0) result.Add((start, count));
                    start = n;
                    count = 1;
                }
            }
            if (start >= 0) result.Add((start, count));
            return result;
        }

        /// <summary>表示上の正規化座標 (0..1, 左上原点) → PDF 座標（pt, 左下原点）。ページの回転も考慮。</summary>
        public static (double x, double y) ToPdf(double nx, double ny, double[] box, int rotate)
        {
            double llx = box[0], lly = box[1], urx = box[2], ury = box[3];
            var w = urx - llx;
            var h = ury - lly;
            switch (rotate)
            {
                case 90: return (llx + ny * w, lly + nx * h);
                case 180: return (llx + (1 - nx) * w, lly + ny * h);
                case 270: return (llx + (1 - ny) * w, ury - nx * h);
                default: return (llx + nx * w, ury - ny * h);
            }
        }

        private static (PdfDict annot, byte[] ap) BuildAnnotation(Highlight h, PdfFile.Page page, PdfRef annotRef)
        {
            var box = page.Box;
            var displayWidth = page.Rotate == 90 || page.Rotate == 270 ? box[3] - box[1] : box[2] - box[0];
            var (ax, ay) = ToPdf(h.X1, h.Y1, box, page.Rotate);
            var (bx, by) = ToPdf(h.X2, h.Y2, box, page.Rotate);
            // 左→右（垂直なら下→上）の向きにそろえ、法線が上を向くようにする
            if (bx < ax - 1e-6 || (Math.Abs(bx - ax) <= 1e-6 && by < ay))
            {
                (ax, bx) = (bx, ax);
                (ay, by) = (by, ay);
            }
            var q = StrokeGeometry.Quad(ax, ay, bx, by, h.Width * displayWidth);
            if (q[1] < q[5])
            {
                // 法線が下向きなら上下を入れ替える（左上, 右上, 左下, 右下 の慣例順）
                q = new[] { q[4], q[5], q[6], q[7], q[0], q[1], q[2], q[3] };
            }
            var xs = new[] { q[0], q[2], q[4], q[6] };
            var ys = new[] { q[1], q[3], q[5], q[7] };
            var rect = new[] { xs.Min(), ys.Min(), xs.Max(), ys.Max() };
            var r = ((h.Color >> 16) & 0xFF) / 255.0;
            var g = ((h.Color >> 8) & 0xFF) / 255.0;
            var b = (h.Color & 0xFF) / 255.0;

            var annot = new PdfDict();
            annot["Type"] = new PdfName("Annot");
            annot["Subtype"] = new PdfName("Highlight");
            annot["Rect"] = Nums(rect);
            annot["QuadPoints"] = Nums(q);
            annot["C"] = Nums(r, g, b);
            annot["CA"] = new PdfNum("1");
            annot["F"] = new PdfNum("4");
            annot["P"] = page.Ref;
            annot["NM"] = new PdfStr(Encoding.ASCII.GetBytes($"markpdf-{h.Id}-{annotRef.Num}"));

            // 外観ストリーム：乗算合成で四角形を塗る（どのビューアでも同じ見た目になるように）
            var content = Encoding.ASCII.GetBytes(string.Format(CultureInfo.InvariantCulture,
                "/G0 gs {0} {1} {2} rg {3} {4} m {5} {6} l {7} {8} l {9} {10} l h f\n",
                Fmt(r), Fmt(g), Fmt(b), Fmt(q[0]), Fmt(q[1]), Fmt(q[2]), Fmt(q[3]), Fmt(q[6]), Fmt(q[7]), Fmt(q[4]), Fmt(q[5])));
            var gs = new PdfDict();
            gs["Type"] = new PdfName("ExtGState");
            gs["BM"] = new PdfName("Multiply");
            gs["CA"] = new PdfNum("1");
            gs["ca"] = new PdfNum("1");
            var extG = new PdfDict();
            extG["G0"] = gs;
            var res = new PdfDict();
            res["ExtGState"] = extG;
            var apDict = new PdfDict();
            apDict["Type"] = new PdfName("XObject");
            apDict["Subtype"] = new PdfName("Form");
            apDict["BBox"] = Nums(rect);
            apDict["Resources"] = res;
            apDict["Length"] = Num(content.Length);

            var ap = new MemoryStream();
            PdfWriter.Write(apDict, ap);
            PdfWriter.Ascii(ap, "\nstream\n");
            ap.Write(content, 0, content.Length);
            PdfWriter.Ascii(ap, "\nendstream");
            return (annot, ap.ToArray());
        }

        private static PdfArray Nums(params double[] v) => new PdfArray(v.Select(x => (PdfObj)new PdfNum(Fmt(x))));

        private static string Fmt(double v)
        {
            var s = v.ToString("0.###", CultureInfo.InvariantCulture);
            return s == "-0" ? "0" : s;
        }
    }
}
