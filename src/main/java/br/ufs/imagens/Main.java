package br.ufs.imagens;

import com.rabbitmq.client.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicBoolean;

public final class Main {
    static final String INPUT = "imagens.originais";
    static final String OUTPUT = "imagens.convertidas";
    static final String ERRORS = "imagens.erros";
    static String env(String name, String fallback) { return System.getenv().getOrDefault(name, fallback); }
    static String[] storageIds() { return env("STORAGE_IDS", "servidor1,servidor2").split(","); }
    static String instance() { return env("HOSTNAME", "local"); }
    static void log(String message) { System.out.printf("[%s] %s%n", instance(), message); }

    static Connection connect() throws Exception {
        ConnectionFactory factory = new ConnectionFactory();
        factory.setHost(env("RABBIT_HOST", "localhost"));
        factory.setUsername(env("RABBIT_USER", "atividade"));
        factory.setPassword(env("RABBIT_PASSWORD", "atividade-local"));
        factory.setAutomaticRecoveryEnabled(true);
        factory.setNetworkRecoveryInterval(3000);
        factory.setRequestedHeartbeat(30);
        for (int attempt = 1; attempt <= 30; attempt++) {
            try { return factory.newConnection(); }
            catch (Exception e) {
                if (attempt == 30) throw e;
                log("Aguardando RabbitMQ, tentativa " + attempt);
                Thread.sleep(2000);
            }
        }
        throw new IllegalStateException("RabbitMQ indisponível");
    }

    static void topology(Channel channel) throws Exception {
        channel.queueDeclare(ERRORS, true, false, false, null);
        Map<String, Object> arguments = Map.of("x-dead-letter-exchange", "",
                "x-dead-letter-routing-key", ERRORS);
        channel.queueDeclare(INPUT, true, false, false, arguments);
        channel.exchangeDeclare(OUTPUT, BuiltinExchangeType.FANOUT, true);
        for (String id : storageIds()) {
            String queue = "armazenamento." + Images.component(id.trim());
            channel.queueDeclare(queue, true, false, false, arguments);
            channel.queueBind(queue, OUTPUT, "");
        }
    }

    // Canal exclusivo de publicação: confirmações não se misturam aos ACKs de consumo.
    static AtomicBoolean setupPublisher(Channel channel) throws Exception {
        channel.confirmSelect();
        AtomicBoolean returned = new AtomicBoolean();
        channel.addReturnListener(message -> returned.set(true));
        return returned;
    }

    static void publish(Channel channel, AtomicBoolean returned, String exchange, String routing,
                        AMQP.BasicProperties properties, byte[] body) throws Exception {
        returned.set(false);
        channel.basicPublish(exchange, routing, true, properties, body);
        channel.waitForConfirmsOrDie(10000);
        if (returned.get()) throw new java.io.IOException("Mensagem sem fila de destino");
    }

    static String header(AMQP.BasicProperties properties, String key) {
        if (properties.getHeaders() == null || properties.getHeaders().get(key) == null)
            throw new IllegalArgumentException("Metadado ausente: " + key);
        return Images.component(properties.getHeaders().get(key).toString());
    }

    static void client(String id, Path directory) throws Exception {
        Images.component(id);
        if (!Files.isDirectory(directory)) throw new IllegalArgumentException("Pasta inexistente: " + directory);
        try (Connection connection = connect(); Channel channel = connection.createChannel()) {
            topology(channel);
            AtomicBoolean returned = setupPublisher(channel);
            List<Path> paths;
            try (var stream = Files.list(directory)) { paths = stream.filter(Images::supported).sorted().toList(); }
            for (Path path : paths) {
                String name = Images.component(path.getFileName().toString());
                byte[] body = Files.readAllBytes(path);
                AMQP.BasicProperties properties = new AMQP.BasicProperties.Builder()
                        .deliveryMode(2).messageId(UUID.randomUUID().toString())
                        .contentType("image/" + Images.format(name))
                        .headers(Map.of("cliente", id, "nome", name, "formato", Images.format(name))).build();
                publish(channel, returned, "", INPUT, properties, body);
                log("ENVIADA cliente=" + id + " arquivo=" + name + " bytes=" + body.length);
            }
            log("Cliente " + id + " terminou: " + paths.size() + " imagens enviadas");
        }
    }

    static void consume(boolean converter, String storageId, Path root) throws Exception {
        try (Connection connection = connect(); Channel consumer = connection.createChannel();
             Channel publisher = connection.createChannel()) {
            topology(consumer);
            AtomicBoolean returned = setupPublisher(publisher);
            consumer.basicQos(1);
            String queue = converter ? INPUT : "armazenamento." + Images.component(storageId);
            consumer.basicConsume(queue, false, (tag, delivery) -> {
                long deliveryTag = delivery.getEnvelope().getDeliveryTag();
                try {
                    var properties = delivery.getProperties();
                    String client = header(properties, "cliente");
                    String name = header(properties, "nome");
                    String format = header(properties, "formato");
                    if (!format.equals(Images.format(name))) throw new IllegalArgumentException("Formato inconsistente");
                    log("RECEBIDA cliente=" + client + " arquivo=" + name + " reentrega=" + delivery.getEnvelope().isRedeliver());
                    if (converter) {
                        Thread.sleep(Long.parseLong(env("CONVERSION_DELAY_MS", "0")));
                        byte[] gray = Images.grayscale(delivery.getBody(), format);
                        publish(publisher, returned, OUTPUT, "", properties, gray);
                        log("CONVERTIDA cliente=" + client + " arquivo=" + name + " antes="
                                + delivery.getBody().length + " depois=" + gray.length);
                    } else {
                        Images.decode(delivery.getBody());
                        Images.save(root, client, name, delivery.getBody());
                        log("SALVA servidor=" + storageId + " cliente=" + client + " arquivo=" + name);
                    }
                    consumer.basicAck(deliveryTag, false);
                } catch (IllegalArgumentException e) {
                    log("ERRO permanente id=" + delivery.getProperties().getMessageId() + " motivo=" + e.getMessage());
                    consumer.basicReject(deliveryTag, false); // Dead-letter: preserva corpo e metadados.
                } catch (Exception e) {
                    log("ERRO transitório: " + e + "; mensagem permanece pendente");
                    try { Thread.sleep(2000); consumer.basicNack(deliveryTag, false, true); }
                    catch (Exception retryError) {
                        // Reiniciar o processo fecha a conexão e devolve entregas sem ACK à fila.
                        log("Falha ao reagendar: " + retryError);
                        System.exit(1);
                    }
                    if (!publisher.isOpen()) System.exit(1);
                }
            }, tag -> { log("Consumo cancelado: " + tag); System.exit(1); });
            log("PRONTO fila=" + queue);
            new CountDownLatch(1).await();
        }
    }

    public static void main(String[] args) throws Exception {
        if (args.length == 0) throw new IllegalArgumentException("Use: topologia | cliente ID PASTA | conversor | armazenador ID PASTA | amostras PASTA | verificar CLIENTES ARMAZENAMENTO SEGUNDOS");
        switch (args[0]) {
            case "topologia" -> {
                try (Connection connection = connect(); Channel channel = connection.createChannel()) {
                    topology(channel); log("Topologia criada para " + String.join(",", storageIds()));
                }
            }
            case "cliente" -> client(args[1], Path.of(args[2]));
            case "conversor" -> consume(true, null, null);
            case "armazenador" -> consume(false, args[1], Path.of(args[2]));
            case "amostras" -> Verifier.samples(Path.of(args[1]));
            case "verificar" -> Verifier.verify(Path.of(args[1]), Path.of(args[2]), Integer.parseInt(args[3]));
            default -> throw new IllegalArgumentException("Comando desconhecido: " + args[0]);
        }
    }
}
