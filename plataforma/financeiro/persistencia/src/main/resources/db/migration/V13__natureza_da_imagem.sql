-- Natureza da imagem. A tabela e a capacidade continuam com o nome "comprovante",
-- para não quebrar os conectores, e passam a guardar duas naturezas:
--   * comprovante: nota, recibo, print do Pix. É prova do pagamento e o padrão;
--   * foto: o que foi comprado. É lembrança, não prova, e fica fora da prestação de contas.
--
-- O default deixa as imagens já guardadas como comprovante, sem UPDATE nas linhas antigas. A natureza
-- não muda depois do envio: o financeiro_app só pode mudar lancamento_id (afterMigrate.sql). Quem errou
-- desfaz o envio e manda de novo.

alter table comprovante add column natureza text not null default 'comprovante'
  check (natureza in ('comprovante', 'foto'));
