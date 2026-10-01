using System.Windows;

namespace MarkPdf
{
    public partial class App : Application
    {
        protected override void OnStartup(StartupEventArgs e)
        {
            base.OnStartup(e);
            var w = new MainWindow();
            w.Show();
            // 「プログラムから開く」やドラッグ＆ドロップでアイコンに落とされた PDF
            if (e.Args.Length > 0) w.OpenFile(e.Args[0]);
        }
    }
}
