import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("org.jetbrains.kotlin.jvm")
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

kotlin {
    compilerOptions { jvmTarget.set(JvmTarget.JVM_17) }
}

dependencies {
    testImplementation("junit:junit:4.13.2")
}

val wordlists = rootProject.file("tools/wordlists")
val dictDir = layout.buildDirectory.dir("dict")

tasks.register<JavaExec>("buildDicts") {
    group = "build"
    description = "Builds en.dict, ar.dict and de.dict from tools/wordlists (run tools/fetch_wordlists.sh once first)."
    classpath = sourceSets["main"].runtimeClasspath
    mainClass.set("dev.yazin.keyboard.engine.dict.DictBuilderKt")
    maxHeapSize = "2g"
    inputs.files(fileTree(wordlists) { include("*.combined") })
    inputs.files(sourceSets["main"].runtimeClasspath)
    outputs.dir(dictDir)
    args(wordlists.absolutePath, dictDir.get().asFile.absolutePath)
}

tasks.withType<Test>().configureEach {
    // LiveServerTest: -Pvoice=<url> tests that server, -Pvoice=venice Venice (key in VENICE_API_KEY); skipped without it.
    val voice = project.findProperty("voice")?.toString()
    systemProperty("voiceUrl", if (voice == null || voice == "true") "" else voice)
    systemProperty("voiceClips", rootProject.file("app/src/main/assets/voice").absolutePath)
    // LiveLlmTest: -Pllm=<url> tests that server, -Pllm=venice Venice; skipped without it.
    val llm = project.findProperty("llm")?.toString()
    systemProperty("llmUrl", if (llm == null || llm == "true") "" else llm)
}

val emojiData = rootProject.file("tools/emoji")

tasks.register<JavaExec>("buildEmoji") {
    group = "build"
    description = "Builds emoji.txt and emoji-words-<lang>.txt from tools/emoji (run tools/fetch_emoji.sh once first)."
    classpath = sourceSets["main"].runtimeClasspath
    mainClass.set("dev.yazin.keyboard.engine.emoji.EmojiBuilderKt")
    val out = layout.buildDirectory.dir("emoji")
    inputs.file(emojiData.resolve("emoji-test.txt"))
    for (lang in listOf("en", "ar")) {
        inputs.file(emojiData.resolve("annotations-$lang.xml"))
        inputs.file(emojiData.resolve("annotations-derived-$lang.xml"))
    }
    inputs.files(sourceSets["main"].runtimeClasspath)
    outputs.dir(out)
    args(emojiData.absolutePath, out.get().asFile.absolutePath)
}
