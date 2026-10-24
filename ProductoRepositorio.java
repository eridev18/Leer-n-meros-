import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/** Carga productos desde un CSV (codigo,nombre,precio) y los busca por código. */
public class ProductoRepositorio {

    public enum Tipo {
        EXACTA("✔ Exacta"),
        SIN_MAYUSCULAS("≈ Igual ignorando mayúsculas/minúsculas"),
        PARECIDA("? Parecida (posible error de lectura)");

        public final String etiqueta;

        Tipo(String etiqueta) {
            this.etiqueta = etiqueta;
        }
    }

    public record Coincidencia(Tipo tipo, Producto producto) {}

    private static final int DISTANCIA_MAXIMA = 1;

    private final List<Producto> productos = new ArrayList<>();

    public ProductoRepositorio(Path csv) throws IOException {
        for (String linea : Files.readAllLines(csv, StandardCharsets.UTF_8)) {
            linea = linea.replace("\uFEFF", "").trim();
            if (linea.isEmpty() || linea.toLowerCase().startsWith("codigo")) continue;
            String[] p = linea.split(",", 3);
            if (p.length < 3) continue;
            productos.add(new Producto(p[0].trim(), p[1].trim(), Double.parseDouble(p[2].trim())));
        }
    }

    public int total() {
        return productos.size();
    }

    /** Busca en este orden: exacta, ignorando mayúsculas, parecida (1 carácter de diferencia). */
    public List<Coincidencia> buscar(String codigo) {
        List<Coincidencia> res = new ArrayList<>();

        for (Producto p : productos) {
            if (p.codigo().equals(codigo)) res.add(new Coincidencia(Tipo.EXACTA, p));
        }
        if (!res.isEmpty()) return res;

        for (Producto p : productos) {
            if (p.codigo().equalsIgnoreCase(codigo)) res.add(new Coincidencia(Tipo.SIN_MAYUSCULAS, p));
        }
        if (!res.isEmpty()) return res;

        String c = codigo.toLowerCase();
        for (Producto p : productos) {
            if (distancia(c, p.codigo().toLowerCase()) <= DISTANCIA_MAXIMA) {
                res.add(new Coincidencia(Tipo.PARECIDA, p));
            }
        }
        return res;
    }

    /** Distancia de Levenshtein. */
    private static int distancia(String a, String b) {
        int[] prev = new int[b.length() + 1];
        int[] cur = new int[b.length() + 1];
        for (int j = 0; j <= b.length(); j++) prev[j] = j;
        for (int i = 1; i <= a.length(); i++) {
            cur[0] = i;
            for (int j = 1; j <= b.length(); j++) {
                int costo = a.charAt(i - 1) == b.charAt(j - 1) ? 0 : 1;
                cur[j] = Math.min(Math.min(cur[j - 1] + 1, prev[j] + 1), prev[j - 1] + costo);
            }
            int[] t = prev;
            prev = cur;
            cur = t;
        }
        return prev[b.length()];
    }
}
