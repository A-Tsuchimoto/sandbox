using System;
using System.Collections.Generic;
using System.IO;
using System.Linq;
using MarkPdf.Core;
using MarkPdf.Core.Pdf;
using Xunit;

namespace MarkPdf.Core.Tests
{
    public class ExporterTests
    {
        private static byte[] Fixture(string name) =>
            File.ReadAllBytes(Path.Combine(AppContext.BaseDirectory, "Fixtures", name));

        private static Highlight Hl(long id, int page, uint color = 0xFFFFE600) =>
            new Highlight(id, page, color, 0.1f, 0.12f, 0.8f, 0.12f, 0.024f);

        private static int AnnotCount(PdfFile pdf, PdfFile.Page page) =>
            (pdf.Resolve(page.Dict["Annots"]) as PdfArray)?.Items.Count ?? 0;

        [Theory]
        [InlineData("classic.pdf")]
        [InlineData("objstm.pdf")]
        public void AppendsHighlightAnnotations(string name)
        {
            var src = Fixture(name);
            var highlights = new Dictionary<int, IReadOnlyList<Highlight>>
            {
                [0] = new[] { Hl(1, 0), Hl(2, 0, 0xFF40C4FF) },
                [1] = new[] { Hl(3, 1) },
                [2] = new[] { Hl(4, 2, 0xFFFF80AB) },
            };
            var outp = new MemoryStream();
            HighlightExporter.Export(src, outp, highlights, 3);
            var result = outp.ToArray();
            Directory.CreateDirectory("export-test");
            File.WriteAllBytes(Path.Combine("export-test", "out-" + name), result);

            // 元のバイト列はそのまま先頭に残る（増分保存）
            Assert.Equal(src, result.Take(src.Length).ToArray());

            var before = new PdfFile(src);
            var after = new PdfFile(result);
            var pb = before.Pages();
            var pa = after.Pages();
            Assert.Equal(3, pa.Count);
            Assert.Equal(AnnotCount(before, pb[0]) + 2, AnnotCount(after, pa[0]));
            Assert.Equal(AnnotCount(before, pb[1]) + 1, AnnotCount(after, pa[1]));
            Assert.True(AnnotCount(before, pb[2]) >= 1); // 既存の注釈は残る
            Assert.Equal(AnnotCount(before, pb[2]) + 1, AnnotCount(after, pa[2]));

            var annot = (PdfDict)after.Resolve(((PdfArray)after.Resolve(pa[0].Dict["Annots"])!).Items.Last())!;
            Assert.Equal("Highlight", ((PdfName)annot["Subtype"]!).Raw);
            Assert.Equal(8, ((PdfArray)annot["QuadPoints"]!).Items.Count);
            Assert.IsType<PdfStream>(after.Resolve(((PdfDict)annot["AP"]!)["N"]));
            Assert.Equal(before.UsesXrefStream, after.UsesXrefStream);
        }

        [Fact]
        public void PageCountMismatchIsRejected()
        {
            var e = Assert.Throws<HighlightExporter.UnsupportedPdfException>(() =>
                HighlightExporter.Export(Fixture("classic.pdf"), new MemoryStream(),
                    new Dictionary<int, IReadOnlyList<Highlight>>(), 5));
            Assert.Equal(HighlightExporter.Reason.PageMismatch, e.Reason);
        }

        [Fact]
        public void GarbageIsRejected()
        {
            var e = Assert.Throws<HighlightExporter.UnsupportedPdfException>(() =>
                HighlightExporter.Export(new byte[] { 1, 2, 3 }, new MemoryStream(),
                    new Dictionary<int, IReadOnlyList<Highlight>>(), 1));
            Assert.Equal(HighlightExporter.Reason.Broken, e.Reason);
        }

        [Fact]
        public void RotationMapping()
        {
            var box = new[] { 0.0, 0.0, 600.0, 800.0 };
            Assert.Equal((0.0, 800.0), HighlightExporter.ToPdf(0, 0, box, 0));
            Assert.Equal((0.0, 0.0), HighlightExporter.ToPdf(0, 0, box, 90));
            Assert.Equal((600.0, 0.0), HighlightExporter.ToPdf(0, 0, box, 180));
            Assert.Equal((600.0, 800.0), HighlightExporter.ToPdf(0, 0, box, 270));
        }
    }
}
