using System;
using System.IO;
using System.Runtime.Serialization;
using System.Runtime.Serialization.Json;

namespace MarkPdf
{
    /// <summary>
    /// アプリの設定と保存場所（%LocalAppData%\MarkPDF）。
    /// Pro かどうかは試作段階ではフラグのみ。製品版では Microsoft Store の購入状態で置き換える。
    /// </summary>
    [DataContract]
    public sealed class AppSettings
    {
        public static readonly string Dir = Path.Combine(
            Environment.GetFolderPath(Environment.SpecialFolder.LocalApplicationData), "MarkPDF");

        private static readonly string FilePath = Path.Combine(Dir, "settings.json");

        public static string HighlightFile(string key) => Path.Combine(Dir, "highlights", key + ".json");

        [DataMember] public bool IsPro { get; set; }
        [DataMember] public int ColorIndex { get; set; }
        [DataMember] public int WidthIndex { get; set; } = 1;
        [DataMember] public bool TextSnap { get; set; } = true;

        private static readonly DataContractJsonSerializer Serializer = new DataContractJsonSerializer(typeof(AppSettings));

        public static AppSettings Load()
        {
            try
            {
                if (File.Exists(FilePath))
                {
                    using (var s = File.OpenRead(FilePath))
                    {
                        if (Serializer.ReadObject(s) is AppSettings a) return a;
                    }
                }
            }
            catch (Exception)
            {
                // 壊れていたら既定値
            }
            return new AppSettings();
        }

        public void Save()
        {
            try
            {
                Directory.CreateDirectory(Dir);
                using (var s = File.Create(FilePath)) Serializer.WriteObject(s, this);
            }
            catch (IOException)
            {
            }
            catch (UnauthorizedAccessException)
            {
            }
        }
    }
}
