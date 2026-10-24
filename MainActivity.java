package mx.demo.lectorcodigos;

import android.Manifest;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.net.Uri;
import android.os.Bundle;
import android.widget.Button;
import android.widget.EditText;
import android.widget.TextView;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.camera.core.CameraSelector;
import androidx.camera.core.ImageCapture;
import androidx.camera.core.ImageCaptureException;
import androidx.camera.core.ImageProxy;
import androidx.camera.core.Preview;
import androidx.camera.lifecycle.ProcessCameraProvider;
import androidx.camera.view.PreviewView;
import androidx.core.content.ContextCompat;

import com.google.common.util.concurrent.ListenableFuture;
import com.google.mlkit.vision.common.InputImage;
import com.google.mlkit.vision.text.TextRecognition;
import com.google.mlkit.vision.text.TextRecognizer;
import com.google.mlkit.vision.text.latin.TextRecognizerOptions;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class MainActivity extends AppCompatActivity {

    private static final int LONGITUD_MINIMA = 3;
    private static final String ARCHIVO_CATALOGO = "productos.csv";

    private PreviewView previewView;
    private TextView salida;
    private EditText campoCodigo;
    private Button btnCapturar, btnFoto, btnCatalogo, btnBuscar;

    private ImageCapture imageCapture;
    private TextRecognizer recognizer;
    private ProductoRepositorio repo = new ProductoRepositorio();
    private final ExecutorService hiloCaptura = Executors.newSingleThreadExecutor();

    private ActivityResultLauncher<String> permisoCamara;
    private ActivityResultLauncher<String> selectorFoto;
    private ActivityResultLauncher<String[]> selectorCsv;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        previewView = findViewById(R.id.preview);
        salida = findViewById(R.id.salida);
        campoCodigo = findViewById(R.id.campoCodigo);
        btnCapturar = findViewById(R.id.btnCapturar);
        btnFoto = findViewById(R.id.btnFoto);
        btnCatalogo = findViewById(R.id.btnCatalogo);
        btnBuscar = findViewById(R.id.btnBuscar);

        recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS);
        cargarCatalogo();

        permisoCamara = registerForActivityResult(new ActivityResultContracts.RequestPermission(), ok -> {
            if (ok) {
                iniciarCamara();
            } else {
                salida.setText("Sin permiso de cámara. Puedes usar el botón \"Foto\" para elegir una imagen.");
            }
        });
        selectorFoto = registerForActivityResult(new ActivityResultContracts.GetContent(), uri -> {
            if (uri != null) leerDesdeUri(uri);
        });
        selectorCsv = registerForActivityResult(new ActivityResultContracts.OpenDocument(), uri -> {
            if (uri != null) importarCatalogo(uri);
        });

        btnCapturar.setOnClickListener(v -> capturar());
        btnFoto.setOnClickListener(v -> selectorFoto.launch("image/*"));
        btnCatalogo.setOnClickListener(v -> selectorCsv.launch(
                new String[]{"text/*", "application/vnd.ms-excel", "application/octet-stream"}));
        btnBuscar.setOnClickListener(v -> buscarManual());

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
                == PackageManager.PERMISSION_GRANTED) {
            iniciarCamara();
        } else {
            permisoCamara.launch(Manifest.permission.CAMERA);
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        hiloCaptura.shutdown();
        recognizer.close();
    }

    // ---------- Cámara ----------

    private void iniciarCamara() {
        ListenableFuture<ProcessCameraProvider> futuro = ProcessCameraProvider.getInstance(this);
        futuro.addListener(() -> {
            try {
                ProcessCameraProvider provider = futuro.get();
                Preview preview = new Preview.Builder().build();
                preview.setSurfaceProvider(previewView.getSurfaceProvider());
                imageCapture = new ImageCapture.Builder()
                        .setCaptureMode(ImageCapture.CAPTURE_MODE_MAXIMIZE_QUALITY)
                        .build();
                provider.unbindAll();
                provider.bindToLifecycle(this, CameraSelector.DEFAULT_BACK_CAMERA, preview, imageCapture);
            } catch (Exception e) {
                salida.setText("No se pudo iniciar la cámara: " + e.getMessage());
            }
        }, ContextCompat.getMainExecutor(this));
    }

    private void capturar() {
        if (imageCapture == null) {
            salida.setText("La cámara no está lista. Usa \"Foto\" para elegir una imagen.");
            return;
        }
        ocupado(true);
        salida.setText("Leyendo imagen…");
        imageCapture.takePicture(hiloCaptura, new ImageCapture.OnImageCapturedCallback() {
            @Override
            public void onCaptureSuccess(@NonNull ImageProxy imagen) {
                int rotacion = imagen.getImageInfo().getRotationDegrees();
                Bitmap bmp = imagen.toBitmap();
                imagen.close();
                procesar(InputImage.fromBitmap(bmp, rotacion));
            }

            @Override
            public void onError(@NonNull ImageCaptureException e) {
                runOnUiThread(() -> {
                    salida.setText("Error al capturar: " + e.getMessage());
                    ocupado(false);
                });
            }
        });
    }

    // ---------- Foto de la galería ----------

    private void leerDesdeUri(Uri uri) {
        try {
            ocupado(true);
            salida.setText("Leyendo imagen…");
            procesar(InputImage.fromFilePath(this, uri));
        } catch (IOException e) {
            salida.setText("No se pudo abrir la imagen: " + e.getMessage());
            ocupado(false);
        }
    }

    // ---------- OCR ----------

    private void procesar(InputImage imagen) {
        recognizer.process(imagen)
                .addOnSuccessListener(res -> {
                    mostrar(extraerCodigos(res.getText()));
                    ocupado(false);
                })
                .addOnFailureListener(e -> {
                    salida.setText("Error de OCR: " + e.getMessage());
                    ocupado(false);
                });
    }

    private List<String> extraerCodigos(String texto) {
        Set<String> codigos = new LinkedHashSet<>();
        for (String token : texto.split("\\s+")) {
            String limpio = token.replaceAll("[^A-Za-z0-9]", "");
            if (limpio.length() >= LONGITUD_MINIMA) codigos.add(limpio);
        }
        return new ArrayList<>(codigos);
    }

    // ---------- Comparación con el catálogo ----------

    private void mostrar(List<String> tokens) {
        if (tokens.isEmpty()) {
            campoCodigo.setText("");
            salida.setText("No se detectó texto. Prueba con más luz o más cerca.");
            return;
        }
        List<String> conCoincidencia = new ArrayList<>();
        for (String t : tokens) {
            if (!repo.buscar(t).isEmpty()) conCoincidencia.add(t);
        }
        // Si hubo coincidencias solo se muestran esas; si no, todo lo leído para corregirlo a mano
        campoCodigo.setText(String.join(" ", conCoincidencia.isEmpty() ? tokens : conCoincidencia));
        salida.setText(resumen(tokens));
    }

    private String resumen(List<String> tokens) {
        StringBuilder sb = new StringBuilder();
        List<String> sinCoincidencia = new ArrayList<>();
        for (String c : tokens) {
            List<ProductoRepositorio.Coincidencia> res = repo.buscar(c);
            if (res.isEmpty()) {
                sinCoincidencia.add(c);
                continue;
            }
            sb.append("Código: ").append(c).append('\n');
            for (ProductoRepositorio.Coincidencia r : res) {
                sb.append("  ").append(r.tipo.etiqueta).append('\n')
                  .append("    ").append(r.producto).append('\n');
            }
            sb.append('\n');
        }
        if (sb.length() == 0) {
            sb.append("✗ Ningún código coincide con el catálogo.\n\n");
        }
        if (!sinCoincidencia.isEmpty()) {
            sb.append("Sin coincidencia: ").append(String.join(", ", sinCoincidencia));
        }
        return sb.toString();
    }

    private void buscarManual() {
        String texto = campoCodigo.getText().toString().trim();
        if (texto.isEmpty()) return;
        salida.setText(resumen(Arrays.asList(texto.split("\\s+"))));
    }

    // ---------- Catálogo ----------

    private void cargarCatalogo() {
        try {
            File guardado = new File(getFilesDir(), ARCHIVO_CATALOGO);
            InputStream in = guardado.exists()
                    ? new FileInputStream(guardado)
                    : getAssets().open(ARCHIVO_CATALOGO);
            repo = new ProductoRepositorio(in);
            salida.setText("Catálogo cargado: " + repo.total() + " productos.");
        } catch (Exception e) {
            repo = new ProductoRepositorio();
            salida.setText("No se pudo cargar el catálogo: " + e.getMessage());
        }
    }

    /** Importa un CSV elegido por el usuario (codigo,nombre,precio) y lo guarda en la app. */
    private void importarCatalogo(Uri uri) {
        try (InputStream in = getContentResolver().openInputStream(uri)) {
            if (in == null) throw new IOException("No se pudo abrir el archivo");
            ByteArrayOutputStream buffer = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) buffer.write(buf, 0, n);
            byte[] datos = buffer.toByteArray();

            // Validar antes de reemplazar el catálogo actual
            ProductoRepositorio nuevo = new ProductoRepositorio(new ByteArrayInputStream(datos));
            if (nuevo.total() == 0) throw new IOException("El archivo no tiene productos válidos");

            try (FileOutputStream out = new FileOutputStream(new File(getFilesDir(), ARCHIVO_CATALOGO))) {
                out.write(datos);
            }
            repo = nuevo;
            salida.setText("Catálogo importado: " + repo.total() + " productos.");
        } catch (Exception e) {
            salida.setText("No se pudo importar el catálogo: " + e.getMessage());
        }
    }

    private void ocupado(boolean b) {
        btnCapturar.setEnabled(!b);
        btnFoto.setEnabled(!b);
        btnBuscar.setEnabled(!b);
    }
}
