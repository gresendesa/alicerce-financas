// Tools @McpTool e seus schemas.
plugins { id("alicerce.java") }

dependencies {
    implementation(project(":financeiro:aplicacao"))
    implementation(project(":seguranca"))
    implementation(libs.spring.ai.mcp.annotations)
    implementation(libs.spring.context)
    implementation(libs.jackson.annotations)
    implementation(libs.jackson.databind)
}
