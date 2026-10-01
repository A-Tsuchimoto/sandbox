using System.IO;
using MarkPdf.Core;
using Xunit;

namespace MarkPdf.Core.Tests
{
    public class LogicTests
    {
        [Theory]
        [InlineData("report.pdf", "report_highlighted.pdf")]
        [InlineData("Scan.PDF", "Scan_highlighted.pdf")]
        [InlineData("a_highlighted.pdf", "a_highlighted.pdf")]
        [InlineData("議事録.pdf", "議事録_highlighted.pdf")]
        public void SuggestsSaveName(string input, string expected) => Assert.Equal(expected, SaveName.Suggest(input));

        [Fact]
        public void StrokeSnapsToAxes()
        {
            Assert.Equal((400.0, 200.0), StrokeGeometry.SnapEnd(100, 200, 400, 230, 1000, 1000));
            Assert.Equal((100.0, 600.0), StrokeGeometry.SnapEnd(100, 200, 120, 600, 1000, 1000));
            Assert.Equal((300.0, 300.0), StrokeGeometry.SnapEnd(100, 100, 300, 300, 1000, 1000));
            Assert.Equal((1000.0, 100.0), StrokeGeometry.SnapEnd(100, 100, 1500, 110, 1000, 800));
        }

        [Fact]
        public void DistanceToSegment()
        {
            Assert.Equal(5, StrokeGeometry.DistanceToSegment(50, 5, 0, 0, 100, 0), 4);
            Assert.Equal(5, StrokeGeometry.DistanceToSegment(-3, 4, 0, 0, 100, 0), 4);
            Assert.Equal(5, StrokeGeometry.DistanceToSegment(3, 4, 0, 0, 0, 0), 4);
        }

        private static int[] Profile(int len, params (int a, int b)[] rows)
        {
            var p = new int[len];
            foreach (var (a, b) in rows) for (var i = a; i <= b; i++) p[i] = 50;
            return p;
        }

        private static float? Center(int[] p, float target) =>
            TextSnap.NearestRunCenter(p, p.Length, target, 5, 10, 3, 40);

        [Fact]
        public void TextSnapFindsNearestLine()
        {
            Assert.Equal(50f, Center(Profile(120, (40, 59)), 56));
            var two = Profile(150, (20, 39), (60, 79));
            Assert.Equal(30f, Center(two, 45));
            Assert.Equal(70f, Center(two, 55));
            Assert.Equal(50f, Center(Profile(120, (40, 48), (50, 59)), 50));
            Assert.Null(Center(Profile(200, (20, 39)), 120));
            Assert.Null(Center(Profile(200, (50, 51)), 50));
            Assert.Null(Center(Profile(200, (20, 120)), 60));
            Assert.Null(Center(Profile(100, (0, 15)), 10));
        }

        [Fact]
        public void StoreRoundTripAndUndo()
        {
            var file = Path.Combine(Path.GetTempPath(), "markpdf-test-" + System.Guid.NewGuid() + ".json");
            var s = new HighlightStore(file);
            var a = s.Add(0, 0xFFFFE600, 0.1f, 0.2f, 0.5f, 0.2f, 0.024f);
            s.Add(1, 0xFF40C4FF, 0.1f, 0.3f, 0.6f, 0.3f, 0.015f);
            s.Remove(a);
            Assert.Empty(s.Page(0));
            Assert.Equal(0, s.Undo());
            Assert.Single(s.Page(0));

            var loaded = new HighlightStore(file);
            loaded.Load();
            Assert.Single(loaded.Page(0));
            Assert.Equal(0xFF40C4FFu, loaded.Page(1)[0].Color);
            Assert.Equal(0.015f, loaded.Page(1)[0].Width, 5);
            File.Delete(file);
        }
    }
}
