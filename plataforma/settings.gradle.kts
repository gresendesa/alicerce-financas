rootProject.name = "plataforma"

include(
    ":convencoes",
    ":seguranca",
    ":financeiro:dominio",
    ":financeiro:aplicacao",
    ":financeiro:adaptador-mcp",
    ":financeiro:adaptador-http",
    ":financeiro:persistencia",
    ":financeiro:app",
)
