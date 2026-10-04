# Validação do projeto

A aplicação é Java 21. Os testes de integração são coordenados por Bash e executam containers com **RabbitMQ real**, dois conversores e dois armazenadores.

## Reproduzir

O [roteiro completo](docs/ROTEIRO_TESTES.md) descreve também a demonstração manual, incluindo o que observar nas filas, nos logs e nas pastas de saída.

```bash
bash scripts/test-integration.sh
```

O comando deve terminar com código **0** e `TODOS OS TESTES PASSARAM`. Requisitos e efeitos sobre os containers estão descritos no [README](README.md#como-testar).

| Cenário | Evidência exigida pelo teste |
| --- | --- |
| Dois clientes e armazenamento redundante | Verificador Java confere entradas nos dois servidores. |
| Nomes iguais e transparência | Subpastas preservadas; pixels de cinza e alfa conferidos. |
| Servidor 2 desligado | Servidor 1 salva; fila do servidor 2 acumula mensagens. |
| Servidor 2 retomado | Todas as entradas aparecem nas duas pastas. |
| Conversores interrompidos antes do ACK | Reentrega registrada como `reentrega=true` para a imagem inédita. |
| Imagem corrompida | Aumenta a fila de erros; imagens válidas continuam sendo salvas. |
| Arquivos ausentes | Verificador retorna código diferente de zero. |
| Conclusão | Filas de trabalho sem mensagens pendentes ou sem ACK. |

## Verificar somente as imagens

```bash
docker compose run --rm verificador
```

Com as entradas incluídas no repositório, o resultado é:

```text
VERIFICAÇÃO OK: 10 imagens em 2 servidores; nomes, dimensões, cinza, transparência e conteúdo conferidos
```

São **10 entradas e 20 saídas**. As originais ficam em `clientes/` e as saídas em `armazenamento/`, geradas durante a execução.

## Limites da validação

Os cenários incluem desligamento de armazenador, SIGKILL dos conversores, entrada inválida e arquivos ausentes. Não simulam perda física de disco, falta de espaço, nem cluster RabbitMQ. Mensagens inválidas dos testes são intencionalmente preservadas na fila `imagens.erros` para inspeção.

## Execução validada em 04/10/2026

`bash scripts/test-integration.sh` terminou com código **0**. Resumo da saída real:

```text
OK: dois conversores e dois armazenadores prontos
OK: filas vazias antes dos cenários de falha
OK: conversão, réplicas, nomes iguais entre clientes e transparência
OK: servidor1 salva enquanto servidor2 está desligado
OK: fila do servidor2 mantém mensagens pendentes
OK: filas vazias após retomada do armazenador
OK: retomada do armazenador
OK: conversores prontos para testar interrupção
OK: imagem inédita entregue e ainda sem ACK
OK: reentrega comprovada após SIGKILL dos conversores
OK: imagem corrompida encaminhada à fila de erros
OK: filas de trabalho vazias após processamento
OK: verificador rejeita arquivos ausentes
TODOS OS TESTES PASSARAM
```

`bash -n scripts/test-integration.sh` e `docker compose config --quiet` também passaram. A falha mostrada no cenário de saída inexistente é intencional: esse teste exige que o verificador rejeite arquivos ausentes.

## Relatório final detalhado

A versão com relatório final foi executada em 04/10/2026: todos os cenários passaram, com saída **0** e duração de **37 segundos**. Depois da restauração e limpeza, o verificador confirmou dez imagens nos dois servidores. As filas de trabalho estavam sem mensagens prontas ou aguardando ACK, com dois conversores e dois armazenadores conectados.

O caminho de falha do relatório também foi conferido: uma simulação do comando Docker retornou código 17 antes de iniciar o ambiente. O script preservou esse código, indicou a etapa de inicialização e não declarou sucesso. Essa simulação testa a apresentação da falha, sem interromper o Docker real.

## Execução em terminal interativo

Em 04/10/2026, o teste completo também passou com pseudo-TTY, em **37 segundos**, com código 0 e dez imagens verificadas nos dois servidores. O executor usa `timeout --foreground` e desabilita stdin e pseudo-TTY nos comandos `docker compose run`, evitando suspensão por SIGTTIN quando o teste é iniciado em um terminal Bash. Um limite adicional de dez segundos força o encerramento de comandos que não respondam à expiração do timeout.
