namespace MarkPdf.Core
{
    /// <summary>保存時に提案するファイル名。</summary>
    public static class SaveName
    {
        private const string Suffix = "_highlighted";

        public static string Suggest(string displayName)
        {
            var name = displayName.Trim();
            if (name.ToLowerInvariant().EndsWith(".pdf")) name = name.Substring(0, name.Length - 4);
            if (name.Length == 0) name = "document";
            if (!name.EndsWith(Suffix)) name += Suffix;
            return name + ".pdf";
        }
    }
}
