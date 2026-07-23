//> using scala 3.3
// pdfbox itself is vendored as an AWT-free patched jar (see tools/patch-pdfbox.scala);
// its transitive deps are declared explicitly.
//> using jar lib/pdfbox-3.0.7-noawt.jar
//> using dep org.apache.pdfbox:fontbox:3.0.7
//> using dep org.apache.pdfbox:pdfbox-io:3.0.7
//> using dep commons-logging:commons-logging:1.3.5
//> using dep com.monovore::decline:2.5.0
//> using dep com.lihaoyi::upickle:4.4.3
//> using test.dep org.scalameta::munit:1.1.1
//> using exclude fixtures/*
//> using exclude tools/*
//> using resourceDir resources
//> using option -deprecation
//> using option -feature
//> using mainClass pdfgate.Main
//> using packaging.output pdfgate
//> using packaging.graalvmJvmId graalvm-community:21.0.2
//> using packaging.graalvmArgs --no-fallback
//> using packaging.graalvmArgs -H:+AddAllCharsets
