using System;
using System.Collections.Generic;
using System.Linq;
using System.Threading;
using System.Windows;
using System.Windows.Controls;
using System.Windows.Controls.Primitives;
using System.Windows.Input;
using System.Windows.Media;
using System.Windows.Media.Imaging;
using System.Windows.Threading;
using MarkPdf.Core;

namespace MarkPdf
{
    public enum Tool { None, Pen, Eraser }

    /// <summary>
    /// ページを縦に並べて表示し、スクロール・ズーム・マーカー／消しゴム操作を受けるビュー。
    ///
    /// - 見えているページ ±1 だけ画像を保持する
    /// - 画像は「等倍の表示幅」の解像度で 1 回だけ描き、拡大中は見えている部分だけ高解像度で重ねる
    /// - ハイライトは「色で塗る → インク層（文字）を重ねる」で乗算合成と同じ見た目にする
    /// </summary>
    public sealed class DocumentView : FrameworkElement, IScrollInfo
    {
        private const double PageMargin = 16;
        private const double Gap = 12;
        private const double MaxBaseWidth = 1000;
        private const int MaxRenderWidth = 1600;
        public const double MinZoom = 0.5;
        public const double MaxZoom = 4;
        private static readonly double[] ZoomSteps = { 0.5, 0.75, 1, 1.25, 1.5, 2, 3, 4 };

        private sealed class Slot
        {
            public BitmapSource? Base;
            public BitmapSource? BaseInk;
            public int BasePx;
            public CancellationTokenSource? BaseCts;
            public BitmapSource? Detail;
            public BitmapSource? DetailInk;
            public Rect DetailRect; // ページ内の正規化矩形
            public string? DetailKey;
            public CancellationTokenSource? DetailCts;
        }

        private PdfSource? _source;
        private double[] _aspects = new double[0];
        private double[] _tops = new double[0];
        private double _pageW;
        private double _pageX;
        private Size _extent;
        private Size _viewport;
        private Vector _offset;
        private readonly Dictionary<int, Slot> _slots = new Dictionary<int, Slot>();
        private readonly DispatcherTimer _detailTimer;
        private readonly TextSnap _snap = new TextSnap();
        private int _lastReportedPage = -1;

        // 操作中の状態
        private enum Drag { None, Pan, Stroke, Erase }
        private Drag _drag;
        private Point _dragStart;
        private Vector _offsetAtDragStart;
        private int _strokePage = -1;
        private double _sx, _sy, _ex, _ey; // ストロークの生の座標（ページ内 DIP）
        private double _dsx, _dsy, _dex, _dey; // 文字吸着後の座標

        public DocumentView()
        {
            ClipToBounds = true;
            Focusable = true;
            _detailTimer = new DispatcherTimer(TimeSpan.FromMilliseconds(150), DispatcherPriority.Background, (s, e) => UpdateDetails(), Dispatcher);
            _detailTimer.Stop();
        }

        // ---- 外部とのやり取り ----

        public HighlightStore? Store { get; set; }
        public Tool Tool { get; set; }
        public uint StrokeColor { get; set; } = Palette.Colors[0];

        /// <summary>ページ幅に対する割合</summary>
        public float StrokeWidth { get; set; } = Palette.Widths[1];

        public bool TextSnapEnabled { get; set; } = true;
        public double Zoom { get; private set; } = 1;

        /// <summary>(先頭に見えているページ, 総ページ数)</summary>
        public event Action<int, int>? PageChanged;

        public event Action<double>? ZoomChanged;

        /// <summary>ハイライトが追加・削除された</summary>
        public event Action? HighlightsChanged;

        public void SetSource(PdfSource? source)
        {
            foreach (var s in _slots.Values)
            {
                s.BaseCts?.Cancel();
                s.DetailCts?.Cancel();
            }
            _slots.Clear();
            _source = source;
            _aspects = source?.PageSizes.Select(s => s.Height / s.Width).ToArray() ?? new double[0];
            _tops = new double[_aspects.Length];
            Zoom = 1;
            _offset = new Vector();
            _lastReportedPage = -1;
            ZoomChanged?.Invoke(Zoom);
            InvalidateMeasure();
            InvalidateVisual();
        }

        public void RefreshHighlights() => InvalidateVisual();

        public void SetCursorForTool()
        {
            Cursor = Tool == Tool.Pen ? Cursors.Cross : Tool == Tool.Eraser ? Cursors.Arrow : Cursors.Hand;
        }

        // ---- ズーム ----

        public void ZoomIn()
        {
            var next = ZoomSteps.Where(s => s > Zoom + 0.01).DefaultIfEmpty(MaxZoom).First();
            SetZoom(next, Center);
        }

        public void ZoomOut()
        {
            var prev = ZoomSteps.Where(s => s < Zoom - 0.01).DefaultIfEmpty(MinZoom).Last();
            SetZoom(prev, Center);
        }

        public void ResetZoom() => SetZoom(1, Center);

        private Point Center => new Point(_viewport.Width / 2, _viewport.Height / 2);

        /// <summary>画面上の点 <paramref name="focus"/> の位置を保ったまま拡大率を変える。</summary>
        public void SetZoom(double z, Point focus)
        {
            z = Math.Max(MinZoom, Math.Min(MaxZoom, z));
            if (_source == null || _aspects.Length == 0 || Math.Abs(z - Zoom) < 0.0001) return;
            var cx = _offset.X + focus.X;
            var cy = _offset.Y + focus.Y;
            var i = PageAt(cy);
            var ry = (cy - _tops[i]) / Math.Max(1, PageHeight(i));
            var rx = (cx - _pageX) / Math.Max(1, _pageW);

            Zoom = z;
            ComputeLayout();
            foreach (var s in _slots.Values) ClearDetail(s);
            SetOffsetClamped(new Vector(_pageX + rx * _pageW - focus.X, _tops[i] + ry * PageHeight(i) - focus.Y));
            ZoomChanged?.Invoke(Zoom);
            InvalidateVisual();
        }

        // ---- レイアウト ----

        private double BaseWidth => Math.Max(100, Math.Min(_viewport.Width - 2 * PageMargin, MaxBaseWidth));

        private double PageHeight(int i) => _pageW * _aspects[i];

        private void ComputeLayout()
        {
            _pageW = BaseWidth * Zoom;
            var y = PageMargin;
            for (var i = 0; i < _aspects.Length; i++)
            {
                if (i > 0) y += Gap;
                _tops[i] = y;
                y += PageHeight(i);
            }
            var contentW = Math.Max(_viewport.Width, _pageW + 2 * PageMargin);
            _pageX = (contentW - _pageW) / 2;
            _extent = _aspects.Length == 0 ? new Size(0, 0) : new Size(contentW, y + PageMargin);
            ScrollOwner?.InvalidateScrollInfo();
        }

        protected override Size MeasureOverride(Size availableSize)
        {
            var w = double.IsInfinity(availableSize.Width) ? 800 : availableSize.Width;
            var h = double.IsInfinity(availableSize.Height) ? 600 : availableSize.Height;
            return new Size(w, h);
        }

        protected override Size ArrangeOverride(Size finalSize)
        {
            _viewport = finalSize;
            ComputeLayout();
            SetOffsetClamped(_offset);
            UpdateVisible();
            return finalSize;
        }

        private Rect PageRect(int i) => new Rect(_pageX - _offset.X, _tops[i] - _offset.Y, _pageW, PageHeight(i));

        /// <summary>y（コンテンツ座標）を含む、またはその直後のページ</summary>
        private int PageAt(double y)
        {
            int lo = 0, hi = _aspects.Length - 1;
            while (lo < hi)
            {
                var mid = (lo + hi) / 2;
                if (_tops[mid] + PageHeight(mid) > y) hi = mid;
                else lo = mid + 1;
            }
            return lo;
        }

        private (int first, int last)? VisibleRange()
        {
            if (_source == null || _aspects.Length == 0 || _viewport.Height <= 0) return null;
            var first = PageAt(_offset.Y);
            var last = first;
            while (last + 1 < _aspects.Length && _tops[last + 1] < _offset.Y + _viewport.Height) last++;
            return (first, last);
        }

        // ---- 描画 ----

        private double Dpi => VisualTreeHelper.GetDpi(this).PixelsPerDip;

        private int BasePx => Math.Min((int)Math.Round(BaseWidth * Dpi), MaxRenderWidth);

        private void UpdateVisible()
        {
            var vr = VisibleRange();
            if (vr == null) return;
            var (first, last) = vr.Value;
            var from = Math.Max(0, first - 1);
            var to = Math.Min(_aspects.Length - 1, last + 1);
            foreach (var i in _slots.Keys.Where(k => k < from || k > to).ToList())
            {
                _slots[i].BaseCts?.Cancel();
                _slots[i].DetailCts?.Cancel();
                _slots.Remove(i);
            }
            for (var i = from; i <= to; i++) EnsureBase(i);
            if (first != _lastReportedPage)
            {
                _lastReportedPage = first;
                PageChanged?.Invoke(first, _aspects.Length);
            }
            _detailTimer.Stop();
            _detailTimer.Start();
        }

        private async void EnsureBase(int i)
        {
            var src = _source;
            if (src == null) return;
            if (!_slots.TryGetValue(i, out var slot)) _slots[i] = slot = new Slot();
            var px = BasePx;
            if (slot.BasePx == px) return;
            slot.BasePx = px;
            slot.BaseCts?.Cancel();
            var cts = slot.BaseCts = new CancellationTokenSource();
            var bmp = await src.RenderAsync(i, px, null, cts.Token);
            if (bmp == null || cts.IsCancellationRequested || _source != src || !_slots.TryGetValue(i, out var cur) || cur != slot) return;
            slot.Base = bmp;
            slot.BaseInk = null;
            // 実際の縦横比（回転などで想定と違う場合に補正）
            var aspect = (double)bmp.PixelHeight / bmp.PixelWidth;
            if (Math.Abs(aspect - _aspects[i]) > 0.01)
            {
                _aspects[i] = aspect;
                ComputeLayout();
            }
            InvalidateVisual();
        }

        private static void ClearDetail(Slot s)
        {
            s.DetailCts?.Cancel();
            s.Detail = null;
            s.DetailInk = null;
            s.DetailKey = null;
        }

        private async void UpdateDetails()
        {
            _detailTimer.Stop();
            var src = _source;
            if (src == null || _drag == Drag.Pan) return;
            var vr = VisibleRange();
            if (vr == null) return;
            var (first, last) = vr.Value;
            var dpi = Dpi;
            var need = _pageW * dpi > BasePx * 1.1;
            foreach (var kv in _slots)
            {
                if (!need || kv.Key < first || kv.Key > last) ClearDetail(kv.Value);
            }
            if (!need) return;
            for (var i = first; i <= last; i++)
            {
                if (src.Rotated[i] || !_slots.TryGetValue(i, out var slot)) continue;
                var pr = PageRect(i);
                var vis = Rect.Intersect(pr, new Rect(_viewport));
                if (vis.IsEmpty || vis.Width < 1 || vis.Height < 1) continue;
                var n = new Rect((vis.X - pr.X) / pr.Width, (vis.Y - pr.Y) / pr.Height, vis.Width / pr.Width, vis.Height / pr.Height);
                var key = $"{_pageW:F1}:{n.X:F4}:{n.Y:F4}:{n.Width:F4}:{n.Height:F4}";
                if (slot.DetailKey == key) continue;
                slot.DetailCts?.Cancel();
                slot.DetailKey = key;
                var cts = slot.DetailCts = new CancellationTokenSource();
                var bmp = await src.RenderAsync(i, (int)Math.Round(vis.Width * dpi), n, cts.Token);
                if (bmp == null || cts.IsCancellationRequested || slot.DetailKey != key || _source != src) continue;
                slot.Detail = bmp;
                slot.DetailInk = null;
                slot.DetailRect = n;
                InvalidateVisual();
            }
        }

        private static readonly Brush BgBrush = Frozen(new SolidColorBrush(Color.FromRgb(0xE3, 0xE5, 0xE8)));
        private static readonly Brush PaperBrush = Brushes.White;
        private static readonly Pen PageBorder = FrozenPen(new Pen(new SolidColorBrush(Color.FromArgb(0x22, 0, 0, 0)), 1));
        private static readonly Dictionary<uint, Brush> ColorBrushes = new Dictionary<uint, Brush>();

        private static Brush Frozen(SolidColorBrush b)
        {
            b.Freeze();
            return b;
        }

        private static Pen FrozenPen(Pen p)
        {
            p.Freeze();
            return p;
        }

        private static Brush BrushFor(uint argb)
        {
            if (!ColorBrushes.TryGetValue(argb, out var b))
            {
                ColorBrushes[argb] = b = Frozen(new SolidColorBrush(Color.FromArgb(0xFF, (byte)(argb >> 16), (byte)(argb >> 8), (byte)argb)));
            }
            return b;
        }

        protected override void OnRender(DrawingContext dc)
        {
            dc.DrawRectangle(BgBrush, null, new Rect(RenderSize));
            var vr = VisibleRange();
            if (vr == null) return;
            var (first, last) = vr.Value;
            for (var i = first; i <= last; i++) DrawPage(dc, i);
        }

        private void DrawPage(DrawingContext dc, int i)
        {
            var r = PageRect(i);
            dc.DrawRectangle(PaperBrush, PageBorder, r);
            _slots.TryGetValue(i, out var slot);
            if (slot?.Base != null) dc.DrawImage(slot.Base, r);
            var detailRect = Rect.Empty;
            if (slot?.Detail != null)
            {
                var n = slot.DetailRect;
                detailRect = new Rect(r.X + n.X * r.Width, r.Y + n.Y * r.Height, n.Width * r.Width, n.Height * r.Height);
                dc.DrawImage(slot.Detail, detailRect);
            }

            // ハイライト：色で塗った上に、同じ形で切り抜いたインク層（文字）を重ねる
            var shapes = new GeometryGroup { FillRule = FillRule.Nonzero };
            var list = Store?.Page(i) ?? (IReadOnlyList<Highlight>)Array.Empty<Highlight>();
            dc.PushClip(new RectangleGeometry(r));
            foreach (var h in list)
            {
                var g = QuadGeometry(r.X + h.X1 * r.Width, r.Y + h.Y1 * r.Height, r.X + h.X2 * r.Width, r.Y + h.Y2 * r.Height, h.Width * r.Width);
                dc.DrawGeometry(BrushFor(h.Color), null, g);
                shapes.Children.Add(g);
            }
            if (_drag == Drag.Stroke && _strokePage == i)
            {
                var g = QuadGeometry(r.X + _dsx * Zoom, r.Y + _dsy * Zoom, r.X + _dex * Zoom, r.Y + _dey * Zoom, StrokeWidth * r.Width);
                dc.DrawGeometry(BrushFor(StrokeColor), null, g);
                shapes.Children.Add(g);
            }
            if (shapes.Children.Count > 0 && slot != null)
            {
                dc.PushClip(shapes);
                if (slot.Detail != null)
                {
                    slot.DetailInk ??= PdfSource.MakeInkLayer(slot.Detail);
                    dc.DrawImage(slot.DetailInk, detailRect);
                }
                else if (slot.Base != null)
                {
                    slot.BaseInk ??= PdfSource.MakeInkLayer(slot.Base);
                    dc.DrawImage(slot.BaseInk, r);
                }
                dc.Pop();
            }
            dc.Pop();
        }

        private static Geometry QuadGeometry(double x1, double y1, double x2, double y2, double width)
        {
            var q = StrokeGeometry.Quad(x1, y1, x2, y2, width);
            var g = new StreamGeometry();
            using (var c = g.Open())
            {
                c.BeginFigure(new Point(q[0], q[1]), true, true);
                c.LineTo(new Point(q[2], q[3]), false, false);
                c.LineTo(new Point(q[6], q[7]), false, false);
                c.LineTo(new Point(q[4], q[5]), false, false);
            }
            g.Freeze();
            return g;
        }

        // ---- マウス・ペン操作 ----

        /// <summary>画面上の点 → (ページ番号, ページ内座標 DIP（等倍換算）)。ページ外なら -1。</summary>
        private (int page, Point local) HitPage(Point p)
        {
            if (_source == null || _aspects.Length == 0) return (-1, default);
            var i = PageAt(p.Y + _offset.Y);
            var r = PageRect(i);
            if (!r.Contains(p)) return (-1, default);
            return (i, new Point((p.X - r.X) / Zoom, (p.Y - r.Y) / Zoom));
        }

        protected override void OnMouseLeftButtonDown(MouseButtonEventArgs e)
        {
            base.OnMouseLeftButtonDown(e);
            Focus();
            if (_source == null) return;
            var p = e.GetPosition(this);
            switch (Tool)
            {
                case Tool.Pen:
                    var (page, local) = HitPage(p);
                    if (page < 0) return;
                    _drag = Drag.Stroke;
                    _strokePage = page;
                    _sx = _ex = local.X;
                    _sy = _ey = local.Y;
                    UpdateSnapped();
                    break;
                case Tool.Eraser:
                    _drag = Drag.Erase;
                    EraseAt(p);
                    break;
                default:
                    _drag = Drag.Pan;
                    _dragStart = p;
                    _offsetAtDragStart = _offset;
                    Cursor = Cursors.ScrollAll;
                    break;
            }
            CaptureMouse();
            InvalidateVisual();
            e.Handled = true;
        }

        protected override void OnMouseMove(MouseEventArgs e)
        {
            base.OnMouseMove(e);
            if (_drag == Drag.None) return;
            var p = e.GetPosition(this);
            switch (_drag)
            {
                case Drag.Pan:
                    SetOffsetClamped(_offsetAtDragStart - (p - _dragStart));
                    break;
                case Drag.Stroke:
                    var r = PageRect(_strokePage);
                    var lx = (p.X - r.X) / Zoom;
                    var ly = (p.Y - r.Y) / Zoom;
                    (_ex, _ey) = StrokeGeometry.SnapEnd(_sx, _sy, lx, ly, r.Width / Zoom, r.Height / Zoom);
                    UpdateSnapped();
                    InvalidateVisual();
                    // ページの端に近づいたら自動スクロール
                    AutoScroll(p);
                    break;
                case Drag.Erase:
                    EraseAt(p);
                    break;
            }
        }

        protected override void OnMouseLeftButtonUp(MouseButtonEventArgs e)
        {
            base.OnMouseLeftButtonUp(e);
            EndDrag(commit: true);
            e.Handled = true;
        }

        protected override void OnLostMouseCapture(MouseEventArgs e)
        {
            base.OnLostMouseCapture(e);
            EndDrag(commit: false);
        }

        private void EndDrag(bool commit)
        {
            var drag = _drag;
            _drag = Drag.None;
            if (IsMouseCaptured) ReleaseMouseCapture();
            if (drag == Drag.Stroke && commit && Store != null && _strokePage >= 0)
            {
                var w = _pageW / Zoom;
                var h = PageHeight(_strokePage) / Zoom;
                if (Math.Sqrt((_ex - _sx) * (_ex - _sx) + (_ey - _sy) * (_ey - _sy)) >= 4)
                {
                    Store.Add(_strokePage, StrokeColor, (float)(_dsx / w), (float)(_dsy / h), (float)(_dex / w), (float)(_dey / h), StrokeWidth);
                    HighlightsChanged?.Invoke();
                }
            }
            _strokePage = -1;
            SetCursorForTool();
            InvalidateVisual();
            _detailTimer.Stop();
            _detailTimer.Start();
        }

        /// <summary>水平／垂直の線なら、近くの文字の行（列）の中央に寄せる</summary>
        private void UpdateSnapped()
        {
            _dsx = _sx;
            _dsy = _sy;
            _dex = _ex;
            _dey = _ey;
            if (!TextSnapEnabled || !_slots.TryGetValue(_strokePage, out var slot) || slot.Base == null) return;
            var bmp = slot.Base;
            var w = _pageW / Zoom;
            var h = PageHeight(_strokePage) / Zoom;
            var kx = bmp.PixelWidth / w;
            var ky = bmp.PixelHeight / h;
            var reader = new BitmapPixelReader(bmp);
            var thickness = StrokeWidth * bmp.PixelWidth;
            if (_ey == _sy)
            {
                var c = _snap.SnapHorizontal(reader, (float)(_sx * kx), (float)(_ex * kx), (float)(_sy * ky), thickness);
                if (c.HasValue) _dsy = _dey = c.Value / ky;
            }
            else if (_ex == _sx)
            {
                var c = _snap.SnapVertical(reader, (float)(_sy * ky), (float)(_ey * ky), (float)(_sx * kx), thickness);
                if (c.HasValue) _dsx = _dex = c.Value / kx;
            }
        }

        private void AutoScroll(Point p)
        {
            const double edge = 24;
            var dy = p.Y < edge ? -12 : p.Y > _viewport.Height - edge ? 12 : 0;
            var dx = p.X < edge ? -12 : p.X > _viewport.Width - edge ? 12 : 0;
            if (dx != 0 || dy != 0) SetOffsetClamped(_offset + new Vector(dx, dy));
        }

        private void EraseAt(Point p)
        {
            var (page, _) = HitPage(p);
            if (page < 0 || Store == null) return;
            var r = PageRect(page);
            var hit = Store.Page(page).LastOrDefault(h =>
                StrokeGeometry.DistanceToSegment(p.X, p.Y, r.X + h.X1 * r.Width, r.Y + h.Y1 * r.Height,
                    r.X + h.X2 * r.Width, r.Y + h.Y2 * r.Height) <= h.Width * r.Width / 2 + 6);
            if (hit == null) return;
            Store.Remove(hit);
            HighlightsChanged?.Invoke();
            InvalidateVisual();
        }

        protected override void OnPreviewMouseWheel(MouseWheelEventArgs e)
        {
            base.OnPreviewMouseWheel(e);
            if ((Keyboard.Modifiers & ModifierKeys.Control) != 0)
            {
                SetZoom(Zoom * (e.Delta > 0 ? 1.1 : 1 / 1.1), e.GetPosition(this));
                e.Handled = true;
            }
            else if ((Keyboard.Modifiers & ModifierKeys.Shift) != 0)
            {
                SetOffsetClamped(_offset + new Vector(-e.Delta * 0.5, 0));
                e.Handled = true;
            }
        }

        // ---- IScrollInfo ----

        public bool CanVerticallyScroll { get; set; }
        public bool CanHorizontallyScroll { get; set; }
        public double ExtentWidth => _extent.Width;
        public double ExtentHeight => _extent.Height;
        public double ViewportWidth => _viewport.Width;
        public double ViewportHeight => _viewport.Height;
        public double HorizontalOffset => _offset.X;
        public double VerticalOffset => _offset.Y;
        public ScrollViewer? ScrollOwner { get; set; }

        private void SetOffsetClamped(Vector v)
        {
            var x = Math.Max(0, Math.Min(v.X, _extent.Width - _viewport.Width));
            var y = Math.Max(0, Math.Min(v.Y, _extent.Height - _viewport.Height));
            var nv = new Vector(x, y);
            if (nv == _offset) return;
            _offset = nv;
            ScrollOwner?.InvalidateScrollInfo();
            UpdateVisible();
            InvalidateVisual();
        }

        private const double Line = 48;

        public void LineUp() => SetVerticalOffset(_offset.Y - Line);
        public void LineDown() => SetVerticalOffset(_offset.Y + Line);
        public void LineLeft() => SetHorizontalOffset(_offset.X - Line);
        public void LineRight() => SetHorizontalOffset(_offset.X + Line);
        public void PageUp() => SetVerticalOffset(_offset.Y - _viewport.Height * 0.9);
        public void PageDown() => SetVerticalOffset(_offset.Y + _viewport.Height * 0.9);
        public void PageLeft() => SetHorizontalOffset(_offset.X - _viewport.Width * 0.9);
        public void PageRight() => SetHorizontalOffset(_offset.X + _viewport.Width * 0.9);
        public void MouseWheelUp() => SetVerticalOffset(_offset.Y - Line * 2);
        public void MouseWheelDown() => SetVerticalOffset(_offset.Y + Line * 2);
        public void MouseWheelLeft() => SetHorizontalOffset(_offset.X - Line * 2);
        public void MouseWheelRight() => SetHorizontalOffset(_offset.X + Line * 2);
        public void SetHorizontalOffset(double offset) => SetOffsetClamped(new Vector(offset, _offset.Y));
        public void SetVerticalOffset(double offset) => SetOffsetClamped(new Vector(_offset.X, offset));
        public Rect MakeVisible(Visual visual, Rect rectangle) => rectangle;

        /// <summary>ページの先頭へ移動</summary>
        public void GoToPage(int page)
        {
            if (page < 0 || page >= _aspects.Length) return;
            SetVerticalOffset(_tops[page] - PageMargin);
        }

        public int CurrentPage => Math.Max(0, _lastReportedPage);
    }

    /// <summary>WPF の画像から <see cref="IPixelReader"/> の形で画素を読む。</summary>
    internal sealed class BitmapPixelReader : IPixelReader
    {
        private readonly BitmapSource _bmp;

        public BitmapPixelReader(BitmapSource bmp) { _bmp = bmp; }

        public int Width => _bmp.PixelWidth;
        public int Height => _bmp.PixelHeight;

        public void Read(int x, int y, int w, int h, int[] buffer) =>
            _bmp.CopyPixels(new Int32Rect(x, y, w, h), buffer, w * 4, 0);
    }
}
