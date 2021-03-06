package com.google.javascript.gents;

import static com.google.common.truth.Truth.assertThat;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import com.google.common.io.Files;
import com.google.gson.Gson;
import com.google.javascript.clutz.DeclarationGeneratorTest;
import com.google.javascript.gents.ModuleRenameLogger.LogItem;
import com.google.javascript.gents.TypeScriptGenerator.GentsResult;
import com.google.javascript.jscomp.SourceFile;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FilenameFilter;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;
import org.junit.runners.Parameterized.Parameters;

@RunWith(Parameterized.class)
public class TypeScriptGeneratorMultiTest {

  static final String multiTestPath = "multiTests";

  // These test are exempt from checking if they include a good.module or goog.provide
  // declaration to maintain the current state of the tests as they were when this CL
  // was created (March 20, 2020).
  private static final ImmutableSet<String> GOOG_MODULE_PROVIDE_EXCEPTIONS =
      ImmutableSet.of(
          "third_party/java_src/clutz/src/test/java/com/google/javascript/gents/multiTests/basenames_dont_collide/a/file.js",
          "third_party/java_src/clutz/src/test/java/com/google/javascript/gents/multiTests/basenames_dont_collide/b/file.js",
          "third_party/java_src/clutz/src/test/java/com/google/javascript/gents/multiTests/converts_ts_module_require/converts_ts_module_require.js",
          "third_party/java_src/clutz/src/test/java/com/google/javascript/gents/multiTests/goog_provide_rewrite/import.js",
          "third_party/java_src/clutz/src/test/java/com/google/javascript/gents/multiTests/import_rewrite/import.js",
          "third_party/java_src/clutz/src/test/java/com/google/javascript/gents/multiTests/jslib_imports/import_mixed_default_namespaces.js",
          "third_party/java_src/clutz/src/test/java/com/google/javascript/gents/multiTests/module_imports/import.js",
          "third_party/java_src/clutz/src/test/java/com/google/javascript/gents/multiTests/provide_imports/import.js",
          "third_party/java_src/clutz/src/test/java/com/google/javascript/gents/multiTests/converts_ts_module_require/x/y/z/convert.js",
          "third_party/java_src/clutz/src/test/java/com/google/javascript/gents/multiTests/jslib_imports/import_provided.js",
          "third_party/java_src/clutz/src/test/java/com/google/javascript/gents/multiTests/provide_imports/import_binding.js");

  public static final FilenameFilter DIR =
      new FilenameFilter() {
        @Override
        public boolean accept(File dir, String name) {
          return new File(dir, name).isDirectory();
        }
      };

  @Parameters(name = "{index}: {0}")
  public static Iterable<File> testCases() {
    return TypeScriptGeneratorTest.getTestInputFiles(DIR, multiTestPath);
  }

  private final File dir;

  public TypeScriptGeneratorMultiTest(File dir) {
    this.dir = dir;
  }

  @Test
  public void runTest() throws Exception {
    TypeScriptGenerator gents = new TypeScriptGenerator(new Options());

    List<File> testFiles =
        getTestInputFilesRecursive(DeclarationGeneratorTest.JS, multiTestPath, dir.getName());

    Set<String> sourceNames = new HashSet<>();
    List<SourceFile> sourceFiles = new ArrayList<>();
    Map<File, String> goldenFiles = new HashMap<>();

    for (final File sourceFile : testFiles) {
      String sourceText = TypeScriptGeneratorTest.getFileText(sourceFile);
      String filepath = sourceFile.getPath();
      sourceFiles.add(SourceFile.fromCode(filepath, sourceText));

      if (!GOOG_MODULE_PROVIDE_EXCEPTIONS.contains(filepath)) {
        TestUtil.assertIsGoogModuleOrProvide(sourceText, filepath);
      }

      if (!filepath.endsWith("_keep.js") && !filepath.endsWith("_keep.es5.js")) {
        sourceNames.add(filepath);

        File goldenFile = DeclarationGeneratorTest.getGoldenFile(sourceFile, ".ts");
        String goldenText = TypeScriptGeneratorTest.getFileText(goldenFile);
        goldenFiles.put(sourceFile, goldenText);
      }
    }

    ByteArrayOutputStream errStream = new ByteArrayOutputStream();
    gents.setErrorStream(new PrintStream(errStream));

    GentsResult gentsResult =
        gents.generateTypeScript(sourceNames, sourceFiles, Collections.emptyList());
    Map<String, String> transpiledSource = gentsResult.sourceFileMap;

    String errors = new String(errStream.toByteArray(), StandardCharsets.UTF_8);
    assertThat(errors).isEmpty();
    assertThat(gents.hasErrors()).isFalse();

    assertThat(transpiledSource).hasSize(sourceNames.size());
    for (File sourceFile : goldenFiles.keySet()) {
      String filepath = sourceFile.getPath();
      String basename = gents.pathUtil.getFilePathWithoutExtension(filepath);
      String goldenText;

      // If the test was run with the UPDATE_GOLDENS environment variable
      // set then the golden file should be updated.
      if (System.getenv("UPDATE_GOLDENS") != null) {
        File goldenFile = DeclarationGeneratorTest.getGoldenFile(sourceFile, ".ts");
        goldenText = transpiledSource.get(basename);
        Files.asCharSink(goldenFile, StandardCharsets.UTF_8).write(goldenText);
      } else {
        goldenText = goldenFiles.get(sourceFile);
      }

      assertThat(transpiledSource).containsKey(basename);
      assertThat(transpiledSource.get(basename)).isEqualTo(goldenText);
    }
    File logFile =
        TypeScriptGeneratorTest.getTestDirPath(multiTestPath)
            .resolve(dir.getName())
            .resolve("log.json")
            .toFile();
    if (logFile.exists()) {
      String goldenLog = TypeScriptGeneratorTest.getFileText(logFile);
      // order independent comparison of the log entries.
      Set<LogItem> goldenLogItems =
          ImmutableSet.copyOf(new Gson().fromJson(goldenLog, LogItem[].class));
      Set<LogItem> emittedLogItems =
          ImmutableSet.copyOf(new Gson().fromJson(gentsResult.moduleRewriteLog, LogItem[].class));

      assertThat(emittedLogItems).isEqualTo(goldenLogItems);
    }
  }

  private static List<File> getTestInputFilesRecursive(FilenameFilter filter, String... dir) {
    ImmutableList.Builder<File> filesBuilder = ImmutableList.builder();

    for (File f :
        Files.fileTraverser()
            .depthFirstPreOrder(TypeScriptGeneratorTest.getTestDirPath(dir).toFile())) {
      if (filter.accept(f.getParentFile(), f.getName())) {
        filesBuilder.add(f);
      }
    }

    return filesBuilder.build();
  }
}
