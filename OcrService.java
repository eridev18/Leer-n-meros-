import net.sourceforge.tess4j.Tesseract;
import net.sourceforge.tess4j.TesseractException;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/** Lee códigos (0-9, A-Z, a-z) desde una imagen usando Tesseract. */
public class OcrService {

    private static final String CARACTERES =
            "0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz";
    private static final int LONGITUD_MINIMA = 3;

    private final Tesseract tesseract;

    public OcrService(String carpetaTessdata) {
        tesseract = new Tesseract();
        tesseract.setDatapath(carpetaTessdata);
        tesseract.setLanguage("eng");
        tesseract.setVariable("tessedit_char_whitelist", CARACTERES);
        tesseract.setVariable("user_defined_dpi", "300");
        // 11 = texto disperso: encuentra códigos sueltos en cualquier parte de la foto
        tesseract.setPageSegMode(11);
    }

    /** Devuelve los códigos detectados (sin repetir), en orden de aparición. */
    public synchronized List<String> leerCodigos(BufferedImage imagen) throws TesseractException {
        String texto = tesseract.doOCR(preprocesar(imagen));
        Set<String> codigos = new LinkedHashSet<>();
        for (String token : texto.split("\\s+")) {
            String limpio = token.replaceAll("[^A-Za-z0-9]", "");
            if (limpio.length() >= LONGITUD_MINIMA) codigos.add(limpio);
        }
        return new ArrayList<>(codigos);
    }

    /** Escala, pasa a gris y binariza (Otsu). Invierte si el texto es claro sobre fondo oscuro. */
    private BufferedImage preprocesar(BufferedImage img) {
        double factor = img.getWidth() < 1000 ? 3.0 : (img.getWidth() < 2000 ? 2.0 : 1.0);
        int w = (int) (img.getWidth() * factor);
        int h = (int) (img.getHeight() * factor);

        BufferedImage gris = new BufferedImage(w, h, BufferedImage.TYPE_BYTE_GRAY);
        Graphics2D g = gris.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
        g.drawImage(img, 0, 0, w, h, Color.WHITE, null);
        g.dispose();

        int[] hist = new int[256];
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                hist[gris.getRaster().getSample(x, y, 0)]++;
            }
        }
        int umbral = otsu(hist, w * h);

        BufferedImage bin = new BufferedImage(w, h, BufferedImage.TYPE_BYTE_GRAY);
        int oscuros = 0;
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                int v = gris.getRaster().getSample(x, y, 0) > umbral ? 255 : 0;
                if (v == 0) oscuros++;
                bin.getRaster().setSample(x, y, 0, v);
            }
        }
        if (oscuros > (w * h) / 2) {
            for (int y = 0; y < h; y++) {
                for (int x = 0; x < w; x++) {
                    bin.getRaster().setSample(x, y, 0, 255 - bin.getRaster().getSample(x, y, 0));
                }
            }
        }
        return bin;
    }

    private int otsu(int[] hist, int total) {
        double suma = 0;
        for (int i = 0; i < 256; i++) suma += i * hist[i];

        double sumaB = 0, maxVarianza = 0;
        int pesoB = 0, umbral = 128;
        for (int t = 0; t < 256; t++) {
            pesoB += hist[t];
            if (pesoB == 0) continue;
            int pesoF = total - pesoB;
            if (pesoF == 0) break;
            sumaB += t * hist[t];
            double mB = sumaB / pesoB;
            double mF = (suma - sumaB) / pesoF;
            double varianza = (double) pesoB * pesoF * (mB - mF) * (mB - mF);
            if (varianza > maxVarianza) {
                maxVarianza = varianza;
                umbral = t;
            }
        }
        return umbral;
    }
}
