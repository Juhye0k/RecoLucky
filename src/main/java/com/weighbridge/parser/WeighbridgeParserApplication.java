package com.weighbridge.parser;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.weighbridge.parser.model.*;
import com.weighbridge.parser.output.JsonWriter;
import com.weighbridge.parser.pipeline.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;

/**
 * CLI 진입점.
 *
 * <pre>{@code
 * java -jar weighbridge-parser.jar <input.json> [output.json]
 * }</pre>
 */
public class WeighbridgeParserApplication {

    private static final Logger log = LoggerFactory.getLogger(WeighbridgeParserApplication.class);

    public static void main(String[] args) throws Exception {
        if (args.length < 1) {
            System.err.println("사용법: java -jar weighbridge-parser.jar <input.json> [output.json]");
            System.exit(1);
        }

        Path inputPath = Path.of(args[0]);
        log.info("파이프라인 시작: {}", inputPath.getFileName());

        ParsedDocument doc;
        try {
            doc = parseFile(inputPath);
        } catch (Exception e) {
            log.error("파이프라인 실패: {}", e.getMessage(), e);
            throw e;
        }

        JsonWriter writer = new JsonWriter();

        if (args.length >= 2) {
            Path outputPath = Path.of(args[1]);
            writer.write(doc, outputPath);
            log.info("파이프라인 완료: 파일 출력 → {}", outputPath);
            System.out.println("Written: " + outputPath);
        } else {
            System.out.println(writer.toJson(doc));
            log.info("파이프라인 완료: stdout 출력");
        }
    }

    static ParsedDocument parseFile(Path inputPath) throws IOException {
        ObjectMapper objectMapper = new ObjectMapper();
        OcrInput input = objectMapper.readValue(inputPath.toFile(), OcrInput.class);

        Preprocessor preprocessor = new Preprocessor();
        Extractor extractor = new Extractor();
        FieldAssigner fieldAssigner = new FieldAssigner();
        Normalizer normalizer = new Normalizer();
        Validator validator = new Validator();

        List<ProcessedLine> processed = preprocessor.process(input);
        List<ClassifiedLine> classified = extractor.classifyLines(processed);
        ParsedDocument doc = fieldAssigner.assign(classified, inputPath.getFileName().toString());
        doc = normalizer.normalize(doc);
        doc = validator.validate(doc);

        return doc;
    }
}
