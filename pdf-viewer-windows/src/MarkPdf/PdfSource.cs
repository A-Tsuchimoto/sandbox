using System;
using System.IO;
using System.Threading;
using System.Threading.Tasks;
using System.Windows;
using System.Windows.Media;
using System.Windows.Media.Imaging;
using WinImaging = global::Windows.Graphics.Imaging;
using WinPdf = global::Windows.Data.Pdf;
using WinStorage = global::Windows.Storage;
using WinStreams = global::Windows.Storage.Streams;

namespace MarkPdf
{
    /// <summary>
    /// 開いている 1 つの PDF。描画は Windows 標準の Windows.Data.Pdf を使う（外部ライブラリ不要）。
    /// 描画要求は 1 つずつ順番に処理し、見えなくなったページの要求はキャンセルできる。
    /// </summary>
    public sealed class PdfSource
    {
        private readonly WinPdf.PdfDocument _doc;
        private readonly SemaphoreSlim _gate = new SemaphoreSlim(1, 1);

        private PdfSource(WinPdf.PdfDocument doc, Size[] sizes, bool[] rotated)
        {
            _doc = doc;
            PageSizes = sizes;
            Rotated = rotated;
        }

        public int PageCount => PageSizes.Length;

        /// <summary>各ページの大きさ（DIP）</summary>
        public Size[] PageSizes { get; }

        /// <summary>回転指定のあるページ（部分描画を使わない）</summary>
        public bool[] Rotated { get; }

        public static async Task<PdfSource> OpenAsync(string path)
        {
            var file = await WinStorage.StorageFile.GetFileFromPathAsync(path);
            var doc = await WinPdf.PdfDocument.LoadFromFileAsync(file);
            var n = (int)doc.PageCount;
            var sizes = new Size[n];
            var rotated = new bool[n];
            for (var i = 0; i < n; i++)
            {
                using (var p = doc.GetPage((uint)i))
                {
                    sizes[i] = new Size(Math.Max(1, p.Size.Width), Math.Max(1, p.Size.Height));
                    rotated[i] = p.Rotation != WinPdf.PdfPageRotation.Normal;
                }
            }
            return new PdfSource(doc, sizes, rotated);
        }

        /// <summary>
        /// ページ全体（<paramref name="source"/> が null）または一部（正規化矩形）を、幅 <paramref name="pixelWidth"/> で描く。
        /// 結果は別スレッドからも使える凍結済み画像（Bgr32）。キャンセルされたら null。
        /// </summary>
        public async Task<BitmapSource?> RenderAsync(int page, int pixelWidth, Rect? source, CancellationToken ct)
        {
            await _gate.WaitAsync();
            try
            {
                if (ct.IsCancellationRequested) return null;
                var stream = new WinStreams.InMemoryRandomAccessStream();
                using (var p = _doc.GetPage((uint)page))
                {
                    var opts = new WinPdf.PdfPageRenderOptions
                    {
                        DestinationWidth = (uint)Math.Max(1, pixelWidth),
                        BitmapEncoderId = WinImaging.BitmapEncoder.BmpEncoderId,
                    };
                    if (source is Rect r)
                    {
                        var s = p.Size;
                        opts.SourceRect = new global::Windows.Foundation.Rect(r.X * s.Width, r.Y * s.Height, r.Width * s.Width, r.Height * s.Height);
                        opts.DestinationHeight = (uint)Math.Max(1, Math.Round(pixelWidth * (r.Height * s.Height) / (r.Width * s.Width)));
                    }
                    await p.RenderToStreamAsync(stream, opts);
                }
                if (ct.IsCancellationRequested) return null;
                // 画像の展開は UI スレッドの外で
                return await Task.Run(() => Decode(stream));
            }
            finally
            {
                _gate.Release();
            }
        }

        private static BitmapSource Decode(WinStreams.InMemoryRandomAccessStream stream)
        {
            using (var s = stream.AsStream())
            {
                s.Position = 0;
                var frame = BitmapDecoder.Create(s, BitmapCreateOptions.PreservePixelFormat, BitmapCacheOption.OnLoad).Frames[0];
                // 透明度は使わない（白背景で描かれている）。Bgr32 に揃えてから実体化する
                var bgr = new WriteableBitmap(new FormatConvertedBitmap(frame, PixelFormats.Bgr32, null, 0));
                bgr.Freeze();
                return bgr;
            }
        }

        /// <summary>
        /// 乗算合成の代わりに重ねる「インク層」を作る。
        /// 各画素から最も明るい成分を除いた色を、暗さに応じた不透明度で持つ（白は透明、黒は不透明の黒）。
        /// マーカーの色の上にこれを重ねると、文字が黒いまま見える（乗算とほぼ同じ見た目）。
        /// </summary>
        public static BitmapSource MakeInkLayer(BitmapSource src)
        {
            var w = src.PixelWidth;
            var h = src.PixelHeight;
            var px = new int[w * h];
            src.CopyPixels(px, w * 4, 0);
            for (var i = 0; i < px.Length; i++)
            {
                var v = px[i];
                var r = (v >> 16) & 0xFF;
                var g = (v >> 8) & 0xFF;
                var b = v & 0xFF;
                var min = r < g ? (r < b ? r : b) : (g < b ? g : b);
                var a = 255 - min;
                px[i] = a == 0 ? 0 : (a << 24) | ((r - min) << 16) | ((g - min) << 8) | (b - min);
            }
            var bmp = BitmapSource.Create(w, h, 96, 96, PixelFormats.Pbgra32, null, px, w * 4);
            bmp.Freeze();
            return bmp;
        }
    }
}
