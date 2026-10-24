import com.github.sarxos.webcam.Webcam;
import com.github.sarxos.webcam.WebcamResolution;

import javax.imageio.ImageIO;
import javax.swing.BorderFactory;
import javax.swing.ImageIcon;
import javax.swing.JButton;
import javax.swing.JFileChooser;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTextArea;
import javax.swing.JTextField;
import javax.swing.SwingConstants;
import javax.swing.SwingUtilities;
import javax.swing.SwingWorker;
import javax.swing.Timer;
import javax.swing.filechooser.FileNameExtensionFilter;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.Image;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.nio.file.Path;
import java.util.List;

/** Lee códigos desde una foto o la cámara y los compara con el catálogo de productos. */
public class AppLector extends JFrame {

    private static final int ANCHO_VISTA = 640;
    private static final int ALTO_VISTA = 480;

    private final OcrService ocr;
    private final ProductoRepositorio repo;

    private final JLabel vista = new JLabel("Abre una foto o activa la cámara", SwingConstants.CENTER);
    private final JTextField campoCodigo = new JTextField();
    private final JTextArea salida = new JTextArea(10, 40);
    private final JButton btnFoto = new JButton("Abrir foto…");
    private final JButton btnCamara = new JButton("Activar cámara");
    private final JButton btnCapturar = new JButton("Capturar y leer");
    private final JButton btnBuscar = new JButton("Buscar");

    private Webcam webcam;
    private Timer timerVista;
    private BufferedImage imagenActual;

    public AppLector(String carpetaTessdata, String rutaCsv) throws IOException {
        super("Lector de códigos");
        ocr = new OcrService(carpetaTessdata);
        repo = new ProductoRepositorio(Path.of(rutaCsv));

        vista.setPreferredSize(new Dimension(ANCHO_VISTA, ALTO_VISTA));
        vista.setBorder(BorderFactory.createLineBorder(Color.GRAY));
        btnCapturar.setEnabled(false);
        salida.setEditable(false);
        salida.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 13));
        salida.setText("Catálogo cargado: " + repo.total() + " productos.");

        JPanel botones = new JPanel(new FlowLayout(FlowLayout.LEFT));
        botones.add(btnFoto);
        botones.add(btnCamara);
        botones.add(btnCapturar);

        JPanel panelCodigo = new JPanel(new BorderLayout(5, 5));
        panelCodigo.add(new JLabel("Código(s):"), BorderLayout.WEST);
        panelCodigo.add(campoCodigo, BorderLayout.CENTER);
        panelCodigo.add(btnBuscar, BorderLayout.EAST);

        JPanel abajo = new JPanel(new BorderLayout(5, 5));
        abajo.add(panelCodigo, BorderLayout.NORTH);
        abajo.add(new JScrollPane(salida), BorderLayout.CENTER);

        setLayout(new BorderLayout(8, 8));
        add(botones, BorderLayout.NORTH);
        add(vista, BorderLayout.CENTER);
        add(abajo, BorderLayout.SOUTH);

        btnFoto.addActionListener(e -> abrirFoto());
        btnCamara.addActionListener(e -> alternarCamara());
        btnCapturar.addActionListener(e -> capturar());
        btnBuscar.addActionListener(e -> buscarManual());
        campoCodigo.addActionListener(e -> buscarManual());

        setDefaultCloseOperation(EXIT_ON_CLOSE);
        addWindowListener(new WindowAdapter() {
            @Override
            public void windowClosing(WindowEvent e) {
                apagarCamara();
            }
        });
        pack();
        setLocationRelativeTo(null);
    }

    // ---------- Foto ----------

    private void abrirFoto() {
        JFileChooser chooser = new JFileChooser();
        chooser.setFileFilter(new FileNameExtensionFilter("Imágenes", "jpg", "jpeg", "png", "bmp", "gif"));
        if (chooser.showOpenDialog(this) != JFileChooser.APPROVE_OPTION) return;

        apagarCamara();
        File archivo = chooser.getSelectedFile();
        try {
            BufferedImage img = ImageIO.read(archivo);
            if (img == null) {
                salida.setText("No se pudo leer la imagen.");
                return;
            }
            imagenActual = img;
            mostrar(img, false);
            leer(img);
        } catch (IOException ex) {
            salida.setText("Error al abrir la imagen: " + ex.getMessage());
        }
    }

    // ---------- Cámara ----------

    private void alternarCamara() {
        if (webcam != null && webcam.isOpen()) {
            apagarCamara();
            return;
        }
        webcam = Webcam.getDefault();
        if (webcam == null) {
            JOptionPane.showMessageDialog(this, "No se encontró ninguna cámara.");
            return;
        }
        try {
            webcam.setViewSize(WebcamResolution.HD.getSize());
        } catch (IllegalArgumentException ex) {
            webcam.setViewSize(WebcamResolution.VGA.getSize());
        }
        webcam.open();

        timerVista = new Timer(60, e -> {
            BufferedImage frame = webcam.getImage();
            if (frame != null) {
                imagenActual = frame;
                mostrar(frame, true);
            }
        });
        timerVista.start();
        btnCamara.setText("Apagar cámara");
        btnCapturar.setEnabled(true);
    }

    private void capturar() {
        BufferedImage frame = imagenActual;
        apagarCamara(); // congela la última imagen en pantalla
        if (frame != null) {
            mostrar(frame, false);
            leer(frame);
        }
    }

    private void apagarCamara() {
        if (timerVista != null) timerVista.stop();
        if (webcam != null && webcam.isOpen()) webcam.close();
        btnCamara.setText("Activar cámara");
        btnCapturar.setEnabled(false);
    }

    // ---------- OCR y comparación ----------

    private void leer(BufferedImage img) {
        ocupado(true);
        salida.setText("Leyendo imagen…");
        new SwingWorker<List<String>, Void>() {
            @Override
            protected List<String> doInBackground() throws Exception {
                return ocr.leerCodigos(img);
            }

            @Override
            protected void done() {
                try {
                    List<String> codigos = get();
                    if (codigos.isEmpty()) {
                        campoCodigo.setText("");
                        salida.setText("No se detectó ningún código. Prueba con mejor luz o más cerca.");
                    } else {
                        campoCodigo.setText(String.join(" ", codigos));
                        salida.setText(comparar(codigos));
                    }
                } catch (Exception ex) {
                    salida.setText("Error de OCR: " + ex.getMessage());
                } finally {
                    ocupado(false);
                }
            }
        }.execute();
    }

    private void buscarManual() {
        String texto = campoCodigo.getText().trim();
        if (texto.isEmpty()) return;
        salida.setText(comparar(List.of(texto.split("\\s+"))));
    }

    private String comparar(List<String> codigos) {
        StringBuilder sb = new StringBuilder();
        for (String c : codigos) {
            sb.append("Código: ").append(c).append('\n');
            List<ProductoRepositorio.Coincidencia> res = repo.buscar(c);
            if (res.isEmpty()) {
                sb.append("  ✗ Sin coincidencias en el catálogo\n");
            }
            for (ProductoRepositorio.Coincidencia r : res) {
                sb.append("  ").append(r.tipo().etiqueta).append('\n')
                  .append("    ").append(r.producto()).append('\n');
            }
            sb.append('\n');
        }
        return sb.toString();
    }

    // ---------- Utilidades de interfaz ----------

    private void ocupado(boolean b) {
        btnFoto.setEnabled(!b);
        btnBuscar.setEnabled(!b);
    }

    private void mostrar(BufferedImage img, boolean rapido) {
        double escala = Math.min((double) ANCHO_VISTA / img.getWidth(), (double) ALTO_VISTA / img.getHeight());
        int w = Math.max(1, (int) (img.getWidth() * escala));
        int h = Math.max(1, (int) (img.getHeight() * escala));
        Image reducida = img.getScaledInstance(w, h, rapido ? Image.SCALE_FAST : Image.SCALE_SMOOTH);
        vista.setText(null);
        vista.setIcon(new ImageIcon(reducida));
    }

    public static void main(String[] args) {
        String tessdata = args.length > 0 ? args[0] : "tessdata";
        String csv = args.length > 1 ? args[1] : "productos.csv";
        SwingUtilities.invokeLater(() -> {
            try {
                new AppLector(tessdata, csv).setVisible(true);
            } catch (Exception e) {
                JOptionPane.showMessageDialog(null, "No se pudo iniciar: " + e.getMessage());
            }
        });
    }
}
