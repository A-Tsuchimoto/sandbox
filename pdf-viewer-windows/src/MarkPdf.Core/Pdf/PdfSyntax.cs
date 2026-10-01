using System;
using System.Collections.Generic;
using System.Globalization;
using System.IO;
using System.Text;

namespace MarkPdf.Core.Pdf
{
    // ハイライト書き出しに必要な分だけの、最小限の PDF 構文（読み取り・書き出し）。
    // 外部ライブラリを使わずアプリを軽く保つため自前で持つ（Android 版の移植）。

    public sealed class PdfFormatException : Exception
    {
        public PdfFormatException(string message) : base(message) { }
    }

    public abstract class PdfObj { }

    /// <summary>数値。精度が変わらないよう元の表記を保持する。</summary>
    public sealed class PdfNum : PdfObj
    {
        public PdfNum(string raw) { Raw = raw; }
        public string Raw { get; }
        public double Double => double.TryParse(Raw, NumberStyles.Float, CultureInfo.InvariantCulture, out var v) ? v : 0;
        public int Int => (int)Double;
    }

    /// <summary>名前（先頭の / を除いた生の表記）。</summary>
    public sealed class PdfName : PdfObj
    {
        public PdfName(string raw) { Raw = raw; }
        public string Raw { get; }
    }

    public sealed class PdfStr : PdfObj
    {
        public PdfStr(byte[] bytes) { Bytes = bytes; }
        public byte[] Bytes { get; }
    }

    public sealed class PdfRef : PdfObj
    {
        public PdfRef(int num, int gen) { Num = num; Gen = gen; }
        public int Num { get; }
        public int Gen { get; }
    }

    public sealed class PdfBool : PdfObj
    {
        public PdfBool(bool value) { Value = value; }
        public bool Value { get; }
    }

    public sealed class PdfNull : PdfObj
    {
        public static readonly PdfNull Instance = new PdfNull();
        private PdfNull() { }
    }

    public sealed class PdfArray : PdfObj
    {
        public PdfArray() { Items = new List<PdfObj>(); }
        public PdfArray(IEnumerable<PdfObj> items) { Items = new List<PdfObj>(items); }
        public List<PdfObj> Items { get; }
    }

    /// <summary>辞書。キーの順序を保つ。</summary>
    public sealed class PdfDict : PdfObj
    {
        private readonly List<KeyValuePair<string, PdfObj>> _items = new List<KeyValuePair<string, PdfObj>>();

        public PdfDict() { }

        public PdfDict(PdfDict source) { _items.AddRange(source._items); }

        public IEnumerable<KeyValuePair<string, PdfObj>> Items => _items;

        public PdfObj? this[string key]
        {
            get
            {
                foreach (var kv in _items) if (kv.Key == key) return kv.Value;
                return null;
            }
            set
            {
                var i = _items.FindIndex(kv => kv.Key == key);
                if (value == null)
                {
                    if (i >= 0) _items.RemoveAt(i);
                }
                else if (i >= 0)
                {
                    _items[i] = new KeyValuePair<string, PdfObj>(key, value);
                }
                else
                {
                    _items.Add(new KeyValuePair<string, PdfObj>(key, value));
                }
            }
        }
    }

    /// <summary>ストリーム（辞書 + ファイル内のデータ位置）。</summary>
    public sealed class PdfStream : PdfObj
    {
        public PdfStream(PdfDict dict, int dataStart, int dataLength)
        {
            Dict = dict;
            DataStart = dataStart;
            DataLength = dataLength;
        }

        public PdfDict Dict { get; }
        public int DataStart { get; }
        public int DataLength { get; }
    }

    /// <summary>バイト列上の字句・構文解析器。</summary>
    public sealed class PdfParser
    {
        private readonly byte[] _buf;
        private readonly int _limit;

        public PdfParser(byte[] buf, int pos = 0)
        {
            _buf = buf;
            _limit = buf.Length;
            Pos = pos;
        }

        public int Pos { get; set; }

        public int Peek() => Pos < _limit ? _buf[Pos] : -1;

        private int At(int i) => i < _limit ? _buf[i] : -1;

        public void SkipWs()
        {
            while (Pos < _limit)
            {
                var c = Peek();
                if (IsWs(c)) Pos++;
                else if (c == '%')
                {
                    while (Pos < _limit && Peek() != '\n' && Peek() != '\r') Pos++;
                }
                else return;
            }
        }

        /// <summary>現在位置が <paramref name="kw"/> で、その後ろが区切り文字なら読み進めて true。</summary>
        public bool Keyword(string kw)
        {
            SkipWs();
            if (Pos + kw.Length > _limit) return false;
            for (var i = 0; i < kw.Length; i++) if (_buf[Pos + i] != kw[i]) return false;
            var after = At(Pos + kw.Length);
            if (after != -1 && !IsWs(after) && !IsDelim(after)) return false;
            Pos += kw.Length;
            return true;
        }

        public int ReadInt()
        {
            SkipWs();
            var t = Token();
            if (!int.TryParse(t, NumberStyles.Integer, CultureInfo.InvariantCulture, out var v))
                throw new PdfFormatException($"整数が必要: '{t}' @{Pos}");
            return v;
        }

        public PdfObj ParseObject()
        {
            SkipWs();
            var c = Peek();
            if (c == -1) throw new PdfFormatException("予期しない終端");
            if (c == '/')
            {
                Pos++;
                return new PdfName(Token());
            }
            if (c == '(') return new PdfStr(LiteralString());
            if (c == '<') return At(Pos + 1) == '<' ? (PdfObj)Dict() : new PdfStr(HexString());
            if (c == '[') return Array();
            if (c == '+' || c == '-' || c == '.' || (c >= '0' && c <= '9')) return NumberOrRef();
            var t = Token();
            switch (t)
            {
                case "true": return new PdfBool(true);
                case "false": return new PdfBool(false);
                case "null": return PdfNull.Instance;
                default: throw new PdfFormatException($"不明なトークン '{t}' @{Pos}");
            }
        }

        private PdfObj NumberOrRef()
        {
            var t = Token();
            if (int.TryParse(t, NumberStyles.None, CultureInfo.InvariantCulture, out var n))
            {
                var save = Pos;
                SkipWs();
                var p = Peek();
                if (p >= '0' && p <= '9' && int.TryParse(Token(), NumberStyles.None, CultureInfo.InvariantCulture, out var g))
                {
                    SkipWs();
                    if (Peek() == 'R')
                    {
                        var after = At(Pos + 1);
                        if (after == -1 || IsWs(after) || IsDelim(after))
                        {
                            Pos++;
                            return new PdfRef(n, g);
                        }
                    }
                }
                Pos = save;
            }
            return new PdfNum(t);
        }

        private PdfDict Dict()
        {
            Pos += 2;
            var d = new PdfDict();
            while (true)
            {
                SkipWs();
                if (Peek() == '>')
                {
                    Pos += 2;
                    return d;
                }
                if (!(ParseObject() is PdfName key)) throw new PdfFormatException($"辞書のキーが名前でない @{Pos}");
                d[key.Raw] = ParseObject();
            }
        }

        private PdfArray Array()
        {
            Pos++;
            var a = new PdfArray();
            while (true)
            {
                SkipWs();
                if (Peek() == ']')
                {
                    Pos++;
                    return a;
                }
                a.Items.Add(ParseObject());
            }
        }

        private byte[] LiteralString()
        {
            Pos++;
            var o = new MemoryStream();
            var depth = 1;
            while (Pos < _limit)
            {
                var c = _buf[Pos++];
                if (c == '(')
                {
                    depth++;
                    o.WriteByte(c);
                }
                else if (c == ')')
                {
                    if (--depth == 0) return o.ToArray();
                    o.WriteByte(c);
                }
                else if (c == '\\')
                {
                    var e = Peek();
                    Pos++;
                    switch (e)
                    {
                        case 'n': o.WriteByte((byte)'\n'); break;
                        case 'r': o.WriteByte((byte)'\r'); break;
                        case 't': o.WriteByte((byte)'\t'); break;
                        case 'b': o.WriteByte(8); break;
                        case 'f': o.WriteByte(12); break;
                        case '\r': if (Peek() == '\n') Pos++; break;
                        case '\n': break;
                        default:
                            if (e >= '0' && e <= '7')
                            {
                                var v = e - '0';
                                for (var k = 0; k < 2; k++)
                                {
                                    var d = Peek();
                                    if (d < '0' || d > '7') break;
                                    v = v * 8 + (d - '0');
                                    Pos++;
                                }
                                o.WriteByte((byte)(v & 0xFF));
                            }
                            else if (e >= 0)
                            {
                                o.WriteByte((byte)e);
                            }
                            break;
                    }
                }
                else
                {
                    o.WriteByte(c);
                }
            }
            throw new PdfFormatException("文字列が閉じていない");
        }

        private byte[] HexString()
        {
            Pos++;
            var o = new MemoryStream();
            var hi = -1;
            while (Pos < _limit)
            {
                var c = _buf[Pos++];
                if (c == '>') break;
                var v = HexValue(c);
                if (v < 0) continue;
                if (hi < 0) hi = v;
                else
                {
                    o.WriteByte((byte)(hi * 16 + v));
                    hi = -1;
                }
            }
            if (hi >= 0) o.WriteByte((byte)(hi * 16));
            return o.ToArray();
        }

        private static int HexValue(int c) =>
            c >= '0' && c <= '9' ? c - '0' : c >= 'a' && c <= 'f' ? c - 'a' + 10 : c >= 'A' && c <= 'F' ? c - 'A' + 10 : -1;

        /// <summary>区切り文字までの 1 トークン</summary>
        public string Token()
        {
            var start = Pos;
            while (Pos < _limit)
            {
                var c = _buf[Pos];
                if (IsWs(c) || IsDelim(c)) break;
                Pos++;
            }
            if (Pos == start) throw new PdfFormatException($"トークンが空 @{Pos}");
            return Encoding.GetEncoding("ISO-8859-1").GetString(_buf, start, Pos - start);
        }

        public static bool IsWs(int c) => c == 0 || c == 9 || c == 10 || c == 12 || c == 13 || c == 32;

        public static bool IsDelim(int c) =>
            c == '(' || c == ')' || c == '<' || c == '>' || c == '[' || c == ']' || c == '{' || c == '}' || c == '/' || c == '%';
    }

    /// <summary>PDF オブジェクトをバイト列に書き出す。文字列は常に 16 進表記にする。</summary>
    public static class PdfWriter
    {
        public static void Write(PdfObj o, Stream outp)
        {
            switch (o)
            {
                case PdfNum n: Ascii(outp, n.Raw); break;
                case PdfName n: Ascii(outp, "/" + n.Raw); break;
                case PdfStr s:
                    outp.WriteByte((byte)'<');
                    foreach (var b in s.Bytes) Ascii(outp, b.ToString("X2"));
                    outp.WriteByte((byte)'>');
                    break;
                case PdfRef r: Ascii(outp, $"{r.Num} {r.Gen} R"); break;
                case PdfBool b: Ascii(outp, b.Value ? "true" : "false"); break;
                case PdfNull _: Ascii(outp, "null"); break;
                case PdfArray a:
                    outp.WriteByte((byte)'[');
                    for (var i = 0; i < a.Items.Count; i++)
                    {
                        if (i > 0) outp.WriteByte((byte)' ');
                        Write(a.Items[i], outp);
                    }
                    outp.WriteByte((byte)']');
                    break;
                case PdfDict d:
                    Ascii(outp, "<<");
                    foreach (var kv in d.Items)
                    {
                        Ascii(outp, "/" + kv.Key + " ");
                        Write(kv.Value, outp);
                        outp.WriteByte((byte)'\n');
                    }
                    Ascii(outp, ">>");
                    break;
                default:
                    throw new PdfFormatException("ストリームは直接書き出せない");
            }
        }

        public static byte[] ToBytes(PdfObj o)
        {
            var m = new MemoryStream();
            Write(o, m);
            return m.ToArray();
        }

        public static void Ascii(Stream outp, string s)
        {
            var b = Encoding.GetEncoding("ISO-8859-1").GetBytes(s);
            outp.Write(b, 0, b.Length);
        }
    }
}
