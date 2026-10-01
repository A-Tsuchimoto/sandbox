using System.Security.Cryptography;
using System.Text;

namespace MarkPdf.Core
{
    /// <summary>
    /// ハイライト保存用のキー。同じファイルを別の場所から開いても復元できるよう「ファイル名 + サイズ」から作る。
    /// </summary>
    public static class DocKey
    {
        public static string For(string fileName, long size)
        {
            using (var sha = SHA256.Create())
            {
                var hash = sha.ComputeHash(Encoding.UTF8.GetBytes(fileName + "\0" + size));
                var sb = new StringBuilder(hash.Length * 2);
                foreach (var b in hash) sb.Append(b.ToString("x2"));
                return sb.ToString();
            }
        }
    }
}
