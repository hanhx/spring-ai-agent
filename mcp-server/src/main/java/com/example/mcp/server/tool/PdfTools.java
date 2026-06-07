package com.example.mcp.server.tool;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.rendering.ImageType;
import org.apache.pdfbox.rendering.PDFRenderer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Service;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;

/**
 * MCP Server 工具：PDF 转图片
 * 将 PDF 文件的每一页转换为图片
 */
@Service
public class PdfTools {

    private static final Logger log = LoggerFactory.getLogger(PdfTools.class);

    @Tool(description = "将PDF文件的每一页转换为图片。参数：pdfPath为PDF文件的绝对路径，outputDir为输出目录（可选，默认与PDF同目录），dpi为图片分辨率（可选，默认150）。返回生成的图片文件路径列表。")
    public String convertPdfToImages(
            @ToolParam(description = "PDF文件的绝对路径") String pdfPath,
            @ToolParam(description = "输出目录路径（可选，默认与PDF同目录）", required = false) String outputDir,
            @ToolParam(description = "图片分辨率DPI（可选，默认150，范围72-300）", required = false) Integer dpi) {

        log.info("[PdfTools] Converting PDF to images: {}", pdfPath);

        // 参数校验
        if (pdfPath == null || pdfPath.isBlank()) {
            return "错误：PDF文件路径不能为空";
        }

        // 检查文件是否存在
        File pdfFile = new File(pdfPath);
        if (!pdfFile.exists()) {
            return "错误：PDF文件不存在: " + pdfPath;
        }

        if (!pdfFile.getName().toLowerCase().endsWith(".pdf")) {
            return "错误：文件不是PDF格式: " + pdfPath;
        }

        // 设置默认值
        if (dpi == null) {
            dpi = 150;
        }
        if (dpi < 72 || dpi > 300) {
            dpi = Math.max(72, Math.min(300, dpi));
            log.info("[PdfTools] DPI adjusted to valid range: {}", dpi);
        }

        // 确定输出目录
        String targetOutputDir;
        if (outputDir == null || outputDir.isBlank()) {
            targetOutputDir = pdfFile.getParent();
        } else {
            targetOutputDir = outputDir;
        }

        // 创建输出目录
        Path outputPath = Paths.get(targetOutputDir);
        try {
            Files.createDirectories(outputPath);
        } catch (IOException e) {
            return "错误：无法创建输出目录: " + e.getMessage();
        }

        // 生成输出文件名前缀
        String baseName = pdfFile.getName().replaceAll("\\.[^.]+$", "");
        String outputPrefix = baseName + "_page_";

        List<String> generatedFiles = new ArrayList<>();

        try (PDDocument document = Loader.loadPDF(pdfFile)) {
            PDFRenderer renderer = new PDFRenderer(document);
            int pageCount = document.getNumberOfPages();

            log.info("[PdfTools] PDF has {} pages, rendering at {} DPI", pageCount, dpi);

            for (int pageIndex = 0; pageIndex < pageCount; pageIndex++) {
                BufferedImage image = renderer.renderImageWithDPI(pageIndex, dpi, ImageType.RGB);

                String fileName = outputPrefix + (pageIndex + 1) + ".png";
                Path filePath = outputPath.resolve(fileName);
                File outputFile = filePath.toFile();

                ImageIO.write(image, "PNG", outputFile);
                generatedFiles.add(outputFile.getAbsolutePath());

                log.info("[PdfTools] Generated: {}", outputFile.getAbsolutePath());
            }

            StringBuilder result = new StringBuilder();
            result.append("PDF转换成功！\n");
            result.append("源文件: ").append(pdfPath).append("\n");
            result.append("总页数: ").append(pageCount).append("\n");
            result.append("分辨率: ").append(dpi).append(" DPI\n");
            result.append("输出目录: ").append(targetOutputDir).append("\n\n");
            result.append("生成的图片文件：\n");
            for (int i = 0; i < generatedFiles.size(); i++) {
                result.append("  第").append(i + 1).append("页: ").append(generatedFiles.get(i)).append("\n");
            }

            return result.toString();

        } catch (IOException e) {
            log.error("[PdfTools] Error converting PDF: {}", e.getMessage(), e);
            return "PDF转换失败: " + e.getMessage();
        }
    }
}