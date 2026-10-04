#!/usr/bin/env bash
# Testes com RabbitMQ real, usando Bash e ferramentas padrão do Linux.
set -Eeuo pipefail

ROOT=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd)
cd "$ROOT"
export LOCAL_UID="${LOCAL_UID:-$(id -u)}"
export LOCAL_GID="${LOCAL_GID:-$(id -g)}"
export CONVERSION_DELAY_MS=0
isolated=$(mktemp -d /tmp/ufs-imagens-XXXXXX)
prefix="_teste_$(basename "$isolated")"
created=()
started=0
started_at=$SECONDS
stage="preparação do ambiente"
completed=()
all_tests_passed=0

dc() {
    # Preservar o grupo do terminal evita suspensão por SIGTTIN em uma sessão Bash.
    # Os clientes e verificadores do teste não precisam de stdin nem de pseudo-TTY.
    if [[ ${1:-} == run ]]; then
        shift
        timeout --foreground --kill-after=10s 180 docker compose run \
            -T --interactive=false "$@"
    else
        timeout --foreground --kill-after=10s 180 docker compose "$@"
    fi
}
queue_state() {
    dc exec -T rabbitmq rabbitmqctl list_queues -q \
        name messages_ready messages_unacknowledged consumers
}
queue_value() {
    queue_state | awk -v queue="$1" -v column="$2" '$1 == queue {print $column}'
}
wait_for() {
    local description=$1 deadline=$((SECONDS + 60))
    shift
    until "$@"; do
        if (( SECONDS >= deadline )); then
            echo "FALHOU: prazo excedido — $description" >&2
            return 1
        fi
        sleep 0.5
    done
    echo "OK: $description"
}
ready() {
    queue_state | awk '
        $1 == "imagens.originais" && $4 == 2 {count++}
        $1 == "armazenamento.servidor1" && $4 == 1 {count++}
        $1 == "armazenamento.servidor2" && $4 == 1 {count++}
        END {exit count != 3}'
}
pending_storage() { [[ $(queue_value armazenamento.servidor2 2) -gt 0 ]]; }
unacked_input() { [[ $(queue_value imagens.originais 3) -gt 0 ]]; }
error_received() { [[ $(queue_value imagens.erros 2) -gt "$baseline" ]]; }
empty_work_queues() {
    queue_state | awk '
        $1 == "imagens.originais" || $1 == "armazenamento.servidor1" || $1 == "armazenamento.servidor2" {
            count++; if ($2 != 0 || $3 != 0) bad=1
        }
        END {exit count != 3 || bad}'
}
verify() { dc run --rm --no-deps verificador; }
fixture() {
    created+=("$1")
    cp -- clientes/cliente1/paisagem.png "clientes/cliente1/$1"
}
cleanup() {
    local status=$? name failure_stage=$stage verification=""
    trap - EXIT
    if (( started )); then
        # Restaurar as instâncias do projeto mesmo após uma falha no teste.
        export CONVERSION_DELAY_MS=0
        dc up -d --no-deps --force-recreate --scale conversor=2 \
            conversor armazenamento2 || { status=1; failure_stage="restauração dos serviços"; }
    fi
    for name in "${created[@]}"; do
        rm -f -- "clientes/cliente1/$name" \
            "armazenamento/servidor1/cliente1/$name" \
            "armazenamento/servidor2/cliente1/$name" || { status=1; failure_stage="limpeza dos arquivos de teste"; }
    done
    rm -f -- "$isolated"/*.png "$isolated/verificacao-negativa.log" \
        || { status=1; failure_stage="limpeza da entrada isolada"; }
    rmdir -- "$isolated" || { status=1; failure_stage="remoção da pasta temporária"; }

    # Só declarar sucesso após restaurar o ambiente e conferir as saídas restantes.
    if (( status == 0 && all_tests_passed )); then
        if wait_for "instâncias restauradas" ready \
                && wait_for "filas de trabalho sem pendências" empty_work_queues \
                && verification=$(verify); then
            completed+=("Ambiente restaurado e arquivos temporários removidos")
        else
            status=1
            failure_stage="verificação final após a limpeza"
        fi
    else
        (( status != 0 )) || status=1
    fi

    printf '\n========== RELATÓRIO FINAL DOS TESTES ==========\n'
    for name in "${completed[@]}"; do
        printf '[OK] %s\n' "$name"
    done
    if (( status == 0 )); then
        printf '\nResultados após a limpeza:\n%s\n' "$verification"
        printf 'RabbitMQ: filas de trabalho vazias, sem mensagens aguardando ACK.\n'
        printf 'Serviços: 2 conversores e 2 armazenadores conectados ao broker.\n'
        printf 'A entrada corrompida foi preservada em imagens.erros para inspeção.\n'
        printf 'Os arquivos originais e suas réplicas permanecem nas pastas do projeto.\n'
        printf 'Duração: %s segundos. Código de saída: 0.\n' "$((SECONDS - started_at))"
        printf '\nTODOS OS TESTES PASSARAM\n'
    else
        printf '\n[FALHOU] Etapa: %s\n' "$failure_stage" >&2
        printf 'Duração: %s segundos. Código de saída: %s.\n' "$((SECONDS - started_at))" "$status" >&2
        printf 'Consulte as mensagens acima e os logs: docker compose logs --tail=100\n' >&2
        printf 'A execução não comprovou todos os cenários.\n' >&2
    fi
    exit "$status"
}
trap cleanup EXIT
trap 'exit 130' INT
trap 'exit 143' TERM

stage="inicialização do RabbitMQ e dos consumidores"
dc up -d --build --scale conversor=2
started=1
dc run --rm --no-deps amostras
wait_for "dois conversores e dois armazenadores prontos" ready
completed+=("RabbitMQ e topologia prontos, com todos os consumidores conectados")
stage="envio, conversão e armazenamento redundante"
dc run --rm --no-deps cliente1
dc run --rm --no-deps cliente2
verify
wait_for "filas vazias antes dos cenários de falha" empty_work_queues
echo "OK: conversão, réplicas, nomes iguais entre clientes e transparência"
completed+=("Dois clientes publicaram; conversores consumiram a fila de trabalho"
            "Fanout: imagens válidas verificadas nos dois armazenadores"
            "Nomes, dimensões, tons de cinza, transparência e réplicas conferidos")

# Uma imagem inédita deve aguardar na fila do servidor desligado.
stage="desligamento e recuperação do armazenador 2"
dc stop armazenamento2
offline="${prefix}_offline.png"
fixture "$offline"
dc run --rm --no-deps cliente1
wait_for "servidor1 salva enquanto servidor2 está desligado" \
    test -f "armazenamento/servidor1/cliente1/$offline"
wait_for "fila do servidor2 mantém mensagens pendentes" pending_storage
test ! -e "armazenamento/servidor2/cliente1/$offline"
dc start armazenamento2
verify
wait_for "filas vazias após retomada do armazenador" empty_work_queues
echo "OK: retomada do armazenador"
completed+=("Armazenador desligado: fila reteve mensagens e entregou após a retomada")

# Atraso somente para interromper uma entrega antes do ACK.
stage="interrupção dos conversores e reentrega antes do ACK"
export CONVERSION_DELAY_MS=15000
dc up -d --no-deps --force-recreate --scale conversor=2 conversor
wait_for "conversores prontos para testar interrupção" ready
interrupted="${prefix}_interrompida.png"
fixture "$interrupted"
cp -- "clientes/cliente1/$interrupted" "$isolated/$interrupted"
dc run --rm --no-deps -v "$isolated:/isolada:ro" \
    cliente1 cliente cliente1 /isolada
wait_for "imagem inédita entregue e ainda sem ACK" unacked_input
dc kill -s SIGKILL conversor
export CONVERSION_DELAY_MS=0
dc up -d --no-deps --force-recreate --scale conversor=2 conversor
verify
dc logs --no-color conversor | \
    awk -v name="$interrupted" 'index($0, name) && index($0, "reentrega=true") {found=1} END {exit !found}'
echo "OK: reentrega comprovada após SIGKILL dos conversores"
completed+=("SIGKILL dos conversores: reentrega comprovada nos logs")

# Imagem inválida não pode impedir que as válidas sejam armazenadas.
stage="isolamento da mensagem inválida e continuidade do processamento"
baseline=$(queue_value imagens.erros 2)
invalid="${prefix}_corrompida.png"
created+=("$invalid")
printf 'isto nao e uma imagem\n' > "clientes/cliente1/$invalid"
valid="${prefix}_apos_erro.png"
fixture "$valid"
dc run --rm --no-deps cliente1
wait_for "imagem corrompida encaminhada à fila de erros" error_received
verify
for server in servidor1 servidor2; do
    test ! -e "armazenamento/$server/cliente1/$invalid"
    test -f "armazenamento/$server/cliente1/$valid"
done
wait_for "filas de trabalho vazias após processamento" empty_work_queues
completed+=("Imagem corrompida isolada na fila de erros, sem bloquear imagens válidas")

# Arquivos ausentes devem produzir código diferente de zero.
stage="verificação negativa de arquivos ausentes"
negative_status=0
dc run --rm --no-deps verificador verificar /clientes /saida-inexistente 0 \
    > "$isolated/verificacao-negativa.log" 2>&1 || negative_status=$?
if (( negative_status != 1 )) || ! awk '/VERIFICAÇÃO FALHOU/ {found=1} END {exit !found}' \
        "$isolated/verificacao-negativa.log"; then
    cat "$isolated/verificacao-negativa.log" >&2
    echo "FALHOU: a verificação negativa não apresentou a rejeição esperada" >&2
    exit 1
fi
rm -f -- "$isolated/verificacao-negativa.log"
echo "OK: verificador rejeita arquivos ausentes"
completed+=("Verificador rejeitou uma saída inexistente, como esperado")
all_tests_passed=1
