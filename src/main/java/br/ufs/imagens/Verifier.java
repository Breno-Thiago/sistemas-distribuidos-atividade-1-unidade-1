package br.ufs.imagens;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.nio.file.*;
import java.util.*;

final class Verifier {
    static void samples(Path root) throws Exception {
        for (int client = 1; client <= 2; client++) {
            Path directory = root.resolve("cliente" + client);
            Files.createDirectories(directory);
            for (String name : List.of("paisagem.png", "foto.jpg", "transparente.png")) {
                Path file = directory.resolve(name);
                if (Files.exists(file)) continue;
                boolean alpha = name.equals("transparente.png");
                BufferedImage image = new BufferedImage(160, 100,
                        alpha ? BufferedImage.TYPE_INT_ARGB : BufferedImage.TYPE_INT_RGB);
                for (int y = 0; y < image.getHeight(); y++) {
                    for (int x = 0; x < image.getWidth(); x++) {
                        int a = alpha ? x * 255 / 159 : 255;
                        int r = (x * client + y) % 256;
                        int g = (y * 2 + client * 50) % 256;
                        int b = (x + y * client + 80) % 256;
                        image.setRGB(x, y, a << 24 | r << 16 | g << 8 | b);
                    }
                }
                if (!ImageIO.write(image, Images.format(name), file.toFile())) throw new IllegalStateException("Falha ao gerar " + file);
                Main.log("Amostra criada: " + file);
            }
        }
    }

    static List<Path> sources(Path root) throws Exception {
        List<Path> sources = new ArrayList<>();
        try (var directories = Files.list(root)) {
            for (Path directory : directories.filter(Files::isDirectory).sorted().toList()) {
                try (var files = Files.list(directory)) {
                    for (Path path : files.filter(Images::supported).sorted().toList()) {
                        try { Images.decode(Files.readAllBytes(path)); sources.add(path); }
                        catch (IllegalArgumentException e) { Main.log("Entrada inválida ignorada pelo verificador: " + path); }
                    }
                }
            }
        }
        if (sources.isEmpty()) throw new IllegalArgumentException("Nenhuma imagem válida para verificar");
        return sources;
    }

    static void checkImage(BufferedImage source, BufferedImage output, String format) {
        if (source.getWidth() != output.getWidth() || source.getHeight() != output.getHeight())
            throw new IllegalStateException("Dimensões diferentes");
        for (int y = 0; y < output.getHeight(); y++) {
            for (int x = 0; x < output.getWidth(); x++) {
                int pixel = output.getRGB(x, y);
                int r = (pixel >> 16) & 255, g = (pixel >> 8) & 255, b = pixel & 255;
                if (r != g || g != b) throw new IllegalStateException("Pixel colorido em " + x + "," + y);
                if (format.equals("png") && (source.getRGB(x, y) >>> 24) != (pixel >>> 24))
                    throw new IllegalStateException("Transparência alterada");
            }
        }
    }

    static void verify(Path inputs, Path outputs, int timeoutSeconds) throws Exception {
        List<Path> sources = sources(inputs);
        long deadline = System.nanoTime() + timeoutSeconds * 1_000_000_000L;
        String lastError = "";
        while (true) {
            try {
                for (Path source : sources) {
                    Path relative = inputs.relativize(source);
                    byte[] expected = Images.grayscale(Files.readAllBytes(source), Images.format(source.toString()));
                    byte[] first = null;
                    for (String id : Main.storageIds()) {
                        Path target = outputs.resolve(id.trim()).resolve(relative);
                        byte[] actual = Files.readAllBytes(target);
                        checkImage(Images.decode(Files.readAllBytes(source)), Images.decode(actual), Images.format(source.toString()));
                        if (!Arrays.equals(expected, actual)) throw new IllegalStateException("Conteúdo não corresponde à conversão atual: " + target);
                        if (first != null && !Arrays.equals(first, actual)) throw new IllegalStateException("Réplicas diferentes: " + relative);
                        first = actual;
                    }
                }
                Main.log("VERIFICAÇÃO OK: " + sources.size() + " imagens em " + Main.storageIds().length
                        + " servidores; nomes, dimensões, cinza, transparência e conteúdo conferidos");
                return;
            } catch (Exception e) { lastError = e.getMessage(); }
            if (System.nanoTime() >= deadline) throw new IllegalStateException("VERIFICAÇÃO FALHOU: " + lastError);
            Thread.sleep(500);
        }
    }
}
