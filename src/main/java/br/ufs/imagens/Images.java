package br.ufs.imagens;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.*;
import java.nio.file.*;
import java.util.Locale;

final class Images {
    static String component(String value) {
        if (value == null || value.isBlank() || value.equals(".") || value.equals("..")
                || value.contains("/") || value.contains("\\") || value.indexOf(0) >= 0)
            throw new IllegalArgumentException("Nome ou identificador inválido: " + value);
        return value;
    }

    static String format(String name) {
        String lower = name.toLowerCase(Locale.ROOT);
        if (lower.endsWith(".png")) return "png";
        if (lower.endsWith(".jpg") || lower.endsWith(".jpeg")) return "jpeg";
        throw new IllegalArgumentException("Formato não suportado: " + name);
    }

    static boolean supported(Path path) {
        try { format(path.getFileName().toString()); return Files.isRegularFile(path); }
        catch (IllegalArgumentException e) { return false; }
    }

    static BufferedImage decode(byte[] bytes) {
        try {
            BufferedImage image = ImageIO.read(new ByteArrayInputStream(bytes));
            if (image == null) throw new IllegalArgumentException("Conteúdo não é uma imagem PNG/JPEG legível");
            return image;
        } catch (IOException e) { throw new IllegalArgumentException("Imagem corrompida", e); }
    }

    static byte[] grayscale(byte[] bytes, String format) throws IOException {
        BufferedImage source = decode(bytes);
        boolean alpha = format.equals("png") && source.getColorModel().hasAlpha();
        BufferedImage gray = new BufferedImage(source.getWidth(), source.getHeight(),
                alpha ? BufferedImage.TYPE_INT_ARGB : BufferedImage.TYPE_BYTE_GRAY);
        for (int y = 0; y < source.getHeight(); y++) {
            for (int x = 0; x < source.getWidth(); x++) {
                int pixel = source.getRGB(x, y);
                int level = (int) Math.round(0.299 * ((pixel >> 16) & 255)
                        + 0.587 * ((pixel >> 8) & 255) + 0.114 * (pixel & 255));
                if (alpha) gray.setRGB(x, y, (pixel & 0xff000000) | level << 16 | level << 8 | level);
                else gray.getRaster().setSample(x, y, 0, level);
            }
        }
        ByteArrayOutputStream result = new ByteArrayOutputStream();
        if (!ImageIO.write(gray, format, result)) throw new IOException("Encoder indisponível: " + format);
        return result.toByteArray();
    }

    static void save(Path root, String client, String name, byte[] bytes) throws IOException {
        Path directory = root.resolve(component(client));
        Files.createDirectories(directory);
        Path target = directory.resolve(component(name));
        Path temporary = Files.createTempFile(directory, ".imagem-", ".tmp");
        try {
            try (var channel = java.nio.channels.FileChannel.open(temporary, StandardOpenOption.WRITE)) {
                var buffer = java.nio.ByteBuffer.wrap(bytes);
                while (buffer.hasRemaining()) channel.write(buffer);
                channel.force(true);
            }
            Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } finally { Files.deleteIfExists(temporary); }
    }
}
