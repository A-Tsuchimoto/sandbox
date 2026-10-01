using System;
using System.IO;
using System.Linq;
using System.Threading.Tasks;
using System.Windows;
using System.Windows.Controls;
using System.Windows.Controls.Primitives;
using System.Windows.Input;
using System.Windows.Media;
using System.Windows.Shapes;
using MarkPdf.Core;
using MarkPdf.Core.Pdf;
using Microsoft.Win32;

namespace MarkPdf
{
    public partial class MainWindow : Window
    {
        private readonly AppSettings _settings = AppSettings.Load();
        private PdfSource? _source;
        private HighlightStore? _store;
        private string? _path;
        private Tool _tool = Tool.None;
        private bool _saving;

        public MainWindow()
        {
            InitializeComponent();

            if (Palette.IsLocked(_settings.ColorIndex, _settings.IsPro) || _settings.ColorIndex >= Palette.Colors.Length)
                _settings.ColorIndex = 0;
            _settings.WidthIndex = Math.Max(0, Math.Min(Palette.Widths.Length - 1, _settings.WidthIndex));

            BtnOpen.Click += (s, e) => ShowOpenDialog();
            BtnOpenBig.Click += (s, e) => ShowOpenDialog();
            BtnSave.Click += (s, e) => SaveAs();
            BtnPen.Click += (s, e) => SetTool(Tool.Pen);
            BtnEraser.Click += (s, e) => SetTool(Tool.Eraser);
            BtnUndo.Click += (s, e) => Undo();
            BtnWidth.Click += (s, e) => CycleWidth();
            BtnSnap.Click += (s, e) => ToggleSnap();
            BtnZoomIn.Click += (s, e) => Doc.ZoomIn();
            BtnZoomOut.Click += (s, e) => Doc.ZoomOut();
            BtnZoom.Click += (s, e) => Doc.ResetZoom();
            BtnMore.Click += (s, e) => ShowMenu();

            Doc.PageChanged += (page, total) => PageText.Text = $"{page + 1} / {total} ページ";
            Doc.ZoomChanged += z => BtnZoom.Content = $"{Math.Round(z * 100)}%";
            Doc.HighlightsChanged += UpdateUi;

            PreviewKeyDown += OnKey;
            DragOver += OnDragOver;
            Drop += OnDrop;

            Doc.StrokeColor = Palette.Colors[_settings.ColorIndex];
            Doc.StrokeWidth = Palette.Widths[_settings.WidthIndex];
            Doc.TextSnapEnabled = _settings.TextSnap;
            BuildPalette();
            UpdateUi();
        }

        // ---- ファイル ----

        private void ShowOpenDialog()
        {
            var dlg = new OpenFileDialog { Filter = "PDF ファイル (*.pdf)|*.pdf", Title = "PDFを開く" };
            if (dlg.ShowDialog(this) == true) OpenFile(dlg.FileName);
        }

        public async void OpenFile(string path)
        {
            PdfSource source;
            try
            {
                path = System.IO.Path.GetFullPath(path);
                Title = "読み込み中… - MarkPDF";
                source = await PdfSource.OpenAsync(path);
            }
            catch (Exception ex)
            {
                Title = _path != null ? $"{System.IO.Path.GetFileName(_path)} - MarkPDF" : "MarkPDF";
                MessageBox.Show(this, "PDFを開けませんでした。\nパスワード付きのPDFには未対応です。\n\n" + ex.Message,
                    "MarkPDF", MessageBoxButton.OK, MessageBoxImage.Warning);
                return;
            }
            var name = System.IO.Path.GetFileName(path);
            var store = new HighlightStore(AppSettings.HighlightFile(DocKey.For(name, new FileInfo(path).Length)));
            await Task.Run(() => store.Load());

            _source = source;
            _store = store;
            _path = path;
            Title = $"{name} - MarkPDF";
            Empty.Visibility = Visibility.Collapsed;
            Doc.Store = store;
            Doc.SetSource(source);
            PageText.Text = $"1 / {source.PageCount} ページ";
            UpdateUi();
            Doc.Focus();
        }

        /// <summary>保存先を選んでもらい（「元の名前_highlighted.pdf」を提案）、ハイライト付きで書き出す</summary>
        private async void SaveAs()
        {
            if (_store == null || _source == null || _path == null || _saving) return;
            if (_store.IsEmpty)
            {
                MessageBox.Show(this, "保存するハイライトがありません。", "MarkPDF", MessageBoxButton.OK, MessageBoxImage.Information);
                return;
            }
            var dlg = new SaveFileDialog
            {
                Filter = "PDF ファイル (*.pdf)|*.pdf",
                DefaultExt = ".pdf",
                FileName = SaveName.Suggest(System.IO.Path.GetFileName(_path)),
                InitialDirectory = System.IO.Path.GetDirectoryName(_path),
                Title = "ハイライト付きで保存",
            };
            if (dlg.ShowDialog(this) != true) return;
            var target = dlg.FileName;
            var src = _path;
            var snapshot = _store.Snapshot();
            var pages = _source.PageCount;

            _saving = true;
            UpdateUi();
            HintText.Text = "保存中…";
            try
            {
                await Task.Run(() =>
                {
                    var bytes = File.ReadAllBytes(src);
                    var ms = new MemoryStream(bytes.Length + 64 * 1024);
                    HighlightExporter.Export(bytes, ms, snapshot, pages);
                    // 書き出しが全部成功してからファイルに書く（途中で失敗しても壊れたファイルを残さない）
                    File.WriteAllBytes(target, ms.ToArray());
                });
                MessageBox.Show(this, $"保存しました：\n{target}", "MarkPDF", MessageBoxButton.OK, MessageBoxImage.Information);
            }
            catch (HighlightExporter.UnsupportedPdfException ex)
            {
                var msg = ex.Reason == HighlightExporter.Reason.Encrypted
                    ? "暗号化されたPDFには保存できません。"
                    : "このPDFの形式には保存が未対応です。";
                MessageBox.Show(this, msg, "MarkPDF", MessageBoxButton.OK, MessageBoxImage.Warning);
            }
            catch (Exception ex)
            {
                MessageBox.Show(this, "保存できませんでした。\n\n" + ex.Message, "MarkPDF", MessageBoxButton.OK, MessageBoxImage.Warning);
            }
            finally
            {
                _saving = false;
                UpdateUi();
            }
        }

        private void OnDragOver(object sender, DragEventArgs e)
        {
            e.Effects = PdfIn(e) != null ? DragDropEffects.Copy : DragDropEffects.None;
            e.Handled = true;
        }

        private void OnDrop(object sender, DragEventArgs e)
        {
            var f = PdfIn(e);
            if (f != null) OpenFile(f);
        }

        private static string? PdfIn(DragEventArgs e) =>
            (e.Data.GetData(DataFormats.FileDrop) as string[])?
                .FirstOrDefault(f => f.EndsWith(".pdf", StringComparison.OrdinalIgnoreCase));

        // ---- ツール ----

        private void SetTool(Tool t)
        {
            if (_source == null) t = Tool.None;
            _tool = _tool == t ? Tool.None : t;
            Doc.Tool = _tool;
            Doc.SetCursorForTool();
            UpdateUi();
        }

        private void Undo()
        {
            if (_store?.Undo() != null) Doc.RefreshHighlights();
            UpdateUi();
        }

        private void SelectColor(int i)
        {
            if (Palette.IsLocked(i, _settings.IsPro))
            {
                ShowPro();
                return;
            }
            _settings.ColorIndex = i;
            _settings.Save();
            Doc.StrokeColor = Palette.Colors[i];
            BuildPalette();
            if (_tool != Tool.Pen && _source != null) SetTool(Tool.Pen);
        }

        private void CycleWidth()
        {
            _settings.WidthIndex = (_settings.WidthIndex + 1) % Palette.Widths.Length;
            _settings.Save();
            Doc.StrokeWidth = Palette.Widths[_settings.WidthIndex];
            UpdateUi();
        }

        private void ToggleSnap()
        {
            _settings.TextSnap = !_settings.TextSnap;
            _settings.Save();
            Doc.TextSnapEnabled = _settings.TextSnap;
            UpdateUi();
        }

        private void BuildPalette()
        {
            Swatches.Children.Clear();
            for (var i = 0; i < Palette.Colors.Length; i++)
            {
                var index = i;
                var c = Palette.Colors[i];
                var locked = Palette.IsLocked(i, _settings.IsPro);
                var selected = i == _settings.ColorIndex;
                var grid = new Grid { Width = 26, Height = 26 };
                grid.Children.Add(new Ellipse
                {
                    Fill = new SolidColorBrush(Color.FromRgb((byte)(c >> 16), (byte)(c >> 8), (byte)c)),
                    Stroke = selected ? (Brush)FindResource("Text") : new SolidColorBrush(Color.FromArgb(0x33, 0, 0, 0)),
                    StrokeThickness = selected ? 2.5 : 1,
                    Margin = new Thickness(selected ? 0 : 3),
                });
                if (locked)
                {
                    grid.Children.Add(new Ellipse { Fill = new SolidColorBrush(Color.FromArgb(0x8C, 0xFF, 0xFF, 0xFF)), Margin = new Thickness(3) });
                    grid.Children.Add(new TextBlock
                    {
                        Text = "", FontFamily = (FontFamily)FindResource("Icons"), FontSize = 11,
                        HorizontalAlignment = HorizontalAlignment.Center, VerticalAlignment = VerticalAlignment.Center,
                        Foreground = new SolidColorBrush(Color.FromRgb(0x3C, 0x40, 0x43)),
                    });
                }
                var b = new Button
                {
                    Style = (Style)FindResource("BarButton"),
                    Padding = new Thickness(3),
                    Content = grid,
                    ToolTip = $"{Palette.ColorNames[i]} ({i + 1})" + (locked ? " — Pro版" : ""),
                };
                b.Click += (s, e) => SelectColor(index);
                Swatches.Children.Add(b);
            }
        }

        private void UpdateUi()
        {
            var hasDoc = _source != null;
            BtnSave.IsEnabled = hasDoc && !_saving;
            BtnPen.IsEnabled = hasDoc;
            BtnEraser.IsEnabled = hasDoc;
            BtnPen.IsChecked = _tool == Tool.Pen;
            BtnEraser.IsChecked = _tool == Tool.Eraser;
            BtnUndo.IsEnabled = _store?.CanUndo == true;
            BtnWidth.Content = "太さ：" + Palette.WidthNames[_settings.WidthIndex];
            BtnSnap.IsChecked = _settings.TextSnap;
            BtnZoomIn.IsEnabled = hasDoc;
            BtnZoomOut.IsEnabled = hasDoc;
            BtnZoom.IsEnabled = hasDoc;
            if (!_saving)
            {
                HintText.Text = !hasDoc ? "PDFを開いてください（Ctrl+O またはドラッグ＆ドロップ）"
                    : _tool == Tool.Pen ? "ドラッグで始点からまっすぐ線を引きます（水平・垂直に吸着）。ホイールでスクロール、Ctrl+ホイールでズーム"
                    : _tool == Tool.Eraser ? "消したいハイライトをクリック／なぞってください"
                    : "ドラッグでスクロール。P：マーカー　E：消しゴム　1〜8：色　Ctrl+ホイール：ズーム";
            }
        }

        // ---- キーボード ----

        private void OnKey(object sender, KeyEventArgs e)
        {
            var ctrl = (Keyboard.Modifiers & ModifierKeys.Control) != 0;
            var key = e.Key == Key.System ? e.SystemKey : e.Key;
            var handled = true;
            if (ctrl)
            {
                switch (key)
                {
                    case Key.O: ShowOpenDialog(); break;
                    case Key.S: SaveAs(); break;
                    case Key.Z: Undo(); break;
                    case Key.OemPlus: case Key.Add: Doc.ZoomIn(); break;
                    case Key.OemMinus: case Key.Subtract: Doc.ZoomOut(); break;
                    case Key.D0: case Key.NumPad0: Doc.ResetZoom(); break;
                    default: handled = false; break;
                }
            }
            else if (Keyboard.Modifiers == ModifierKeys.None)
            {
                if (key >= Key.D1 && key <= Key.D8) SelectColor(key - Key.D1);
                else if (key >= Key.NumPad1 && key <= Key.NumPad8) SelectColor(key - Key.NumPad1);
                else switch (key)
                {
                    case Key.P: SetTool(Tool.Pen); break;
                    case Key.E: SetTool(Tool.Eraser); break;
                    case Key.Escape: if (_tool != Tool.None) SetTool(_tool); break;
                    case Key.T: ToggleSnap(); break;
                    case Key.W: CycleWidth(); break;
                    case Key.PageDown: case Key.Space: Doc.PageDown(); break;
                    case Key.PageUp: Doc.PageUp(); break;
                    case Key.Down: Doc.LineDown(); break;
                    case Key.Up: Doc.LineUp(); break;
                    case Key.Left: Doc.LineLeft(); break;
                    case Key.Right: Doc.LineRight(); break;
                    case Key.Home: Doc.GoToPage(0); break;
                    case Key.End: if (_source != null) Doc.GoToPage(_source.PageCount - 1); break;
                    default: handled = false; break;
                }
            }
            else
            {
                handled = false;
            }
            if (handled) e.Handled = true;
        }

        // ---- メニュー / Pro ----

        private void ShowMenu()
        {
            var menu = new ContextMenu { PlacementTarget = BtnMore, Placement = PlacementMode.Bottom };
            var pro = new MenuItem { Header = "Pro版について" };
            pro.Click += (s, e) => ShowPro();
            menu.Items.Add(pro);
            var keys = new MenuItem { Header = "ショートカット一覧" };
            keys.Click += (s, e) => MessageBox.Show(this,
                "Ctrl+O　開く\nCtrl+S　ハイライト付きで保存\nCtrl+Z　元に戻す\n\nP　マーカー\nE　消しゴム\nEsc　ツール解除\n1〜8　色\nW　太さ\nT　文字吸着のオン／オフ\n\nCtrl+ホイール / Ctrl+＋ / Ctrl+－　ズーム\nCtrl+0　100%\nShift+ホイール　横スクロール\nPageUp / PageDown / Home / End　ページ移動",
                "ショートカット", MessageBoxButton.OK, MessageBoxImage.None);
            menu.Items.Add(keys);
#if DEBUG
            menu.Items.Add(new Separator());
            var debug = new MenuItem { Header = _settings.IsPro ? "試作：Proを無効化" : "試作：Proを有効化" };
            debug.Click += (s, e) =>
            {
                _settings.IsPro = !_settings.IsPro;
                if (Palette.IsLocked(_settings.ColorIndex, _settings.IsPro)) _settings.ColorIndex = 0;
                _settings.Save();
                Doc.StrokeColor = Palette.Colors[_settings.ColorIndex];
                BuildPalette();
            };
            menu.Items.Add(debug);
#endif
            menu.IsOpen = true;
        }

        private void ShowPro()
        {
            var msg = _settings.IsPro
                ? "Pro版が有効です。ご利用ありがとうございます！"
                : "無料版ではマーカー2色まで使えます。\n\nPro版（数百円・買い切り）では全8色が解放されます。広告は無料版・Pro版とも表示しません。\n\n※購入機能は試作段階のため未実装です。";
            MessageBox.Show(this, msg, "MarkPDF Pro（買い切り）", MessageBoxButton.OK, MessageBoxImage.Information);
        }
    }
}
