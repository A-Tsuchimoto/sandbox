using System;
using System.Collections.Generic;
using System.IO;
using System.Linq;
using System.Runtime.Serialization;
using System.Runtime.Serialization.Json;

namespace MarkPdf.Core
{
    /// <summary>
    /// 1 つの PDF に付いたハイライト。PDF 本体は書き換えず、アプリ内の JSON に自動保存する。
    /// 追加・削除は Undo できる。
    /// </summary>
    public sealed class HighlightStore
    {
        private const int MaxHistory = 100;

        private readonly string _file;
        private readonly Dictionary<int, List<Highlight>> _byPage = new Dictionary<int, List<Highlight>>();
        private readonly LinkedList<(bool added, Highlight h, int index)> _history = new LinkedList<(bool, Highlight, int)>();
        private long _nextId = 1;

        public HighlightStore(string file)
        {
            _file = file;
        }

        public bool CanUndo => _history.Count > 0;

        public bool IsEmpty => _byPage.Values.All(l => l.Count == 0);

        public IReadOnlyList<Highlight> Page(int page) =>
            _byPage.TryGetValue(page, out var list) ? list : (IReadOnlyList<Highlight>)Array.Empty<Highlight>();

        /// <summary>書き出し用のコピー（ページ番号 → 描いた順）</summary>
        public IDictionary<int, IReadOnlyList<Highlight>> Snapshot() =>
            _byPage.ToDictionary(kv => kv.Key, kv => (IReadOnlyList<Highlight>)kv.Value.ToList());

        public Highlight Add(int page, uint color, float x1, float y1, float x2, float y2, float width)
        {
            var h = new Highlight(_nextId++, page, color, x1, y1, x2, y2, width);
            List(page).Add(h);
            Push((true, h, -1));
            Save();
            return h;
        }

        public void Remove(Highlight h)
        {
            if (!_byPage.TryGetValue(h.Page, out var list)) return;
            var index = list.FindIndex(x => x.Id == h.Id);
            if (index < 0) return;
            list.RemoveAt(index);
            Push((false, h, index));
            Save();
        }

        /// <summary>直前の操作を取り消し、影響したページ番号を返す。</summary>
        public int? Undo()
        {
            if (_history.Count == 0) return null;
            var (added, h, index) = _history.Last!.Value;
            _history.RemoveLast();
            var list = List(h.Page);
            if (added) list.RemoveAll(x => x.Id == h.Id);
            else list.Insert(Math.Min(index, list.Count), h);
            Save();
            return h.Page;
        }

        private List<Highlight> List(int page)
        {
            if (!_byPage.TryGetValue(page, out var list)) _byPage[page] = list = new List<Highlight>();
            return list;
        }

        private void Push((bool, Highlight, int) change)
        {
            _history.AddLast(change);
            while (_history.Count > MaxHistory) _history.RemoveFirst();
        }

        // ---- JSON（Android 版と同じ形式） ----

        [DataContract]
        private sealed class FileDto
        {
            [DataMember(Name = "version", Order = 0)] public int Version { get; set; } = 1;
            [DataMember(Name = "highlights", Order = 1)] public List<ItemDto> Highlights { get; set; } = new List<ItemDto>();
        }

        [DataContract]
        private sealed class ItemDto
        {
            [DataMember(Name = "id")] public long Id { get; set; }
            [DataMember(Name = "page")] public int Page { get; set; }
            /// <summary>Android 版に合わせて符号付き 32bit の ARGB</summary>
            [DataMember(Name = "color")] public int Color { get; set; }
            [DataMember(Name = "x1")] public double X1 { get; set; }
            [DataMember(Name = "y1")] public double Y1 { get; set; }
            [DataMember(Name = "x2")] public double X2 { get; set; }
            [DataMember(Name = "y2")] public double Y2 { get; set; }
            [DataMember(Name = "w")] public double W { get; set; }
        }

        private static readonly DataContractJsonSerializer Serializer = new DataContractJsonSerializer(typeof(FileDto));

        public void Load()
        {
            if (!File.Exists(_file)) return;
            try
            {
                FileDto? dto;
                using (var s = File.OpenRead(_file)) dto = Serializer.ReadObject(s) as FileDto;
                if (dto?.Highlights == null) return;
                foreach (var o in dto.Highlights)
                {
                    var h = new Highlight(o.Id, o.Page, unchecked((uint)o.Color),
                        (float)o.X1, (float)o.Y1, (float)o.X2, (float)o.Y2, (float)o.W);
                    List(h.Page).Add(h);
                    if (h.Id >= _nextId) _nextId = h.Id + 1;
                }
            }
            catch (Exception)
            {
                // 壊れたファイルは無視（ハイライトなしで開く）
            }
        }

        private void Save()
        {
            var dto = new FileDto();
            foreach (var h in _byPage.Values.SelectMany(l => l))
            {
                dto.Highlights.Add(new ItemDto
                {
                    Id = h.Id, Page = h.Page, Color = unchecked((int)h.Color),
                    X1 = h.X1, Y1 = h.Y1, X2 = h.X2, Y2 = h.Y2, W = h.Width,
                });
            }
            try
            {
                Directory.CreateDirectory(Path.GetDirectoryName(_file)!);
                var tmp = _file + ".tmp";
                using (var s = File.Create(tmp)) Serializer.WriteObject(s, dto);
                if (File.Exists(_file)) File.Replace(tmp, _file, null);
                else File.Move(tmp, _file);
            }
            catch (IOException)
            {
                // 保存できなくても編集は続けられる
            }
            catch (UnauthorizedAccessException)
            {
            }
        }
    }
}
