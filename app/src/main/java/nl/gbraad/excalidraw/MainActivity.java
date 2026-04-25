package nl.gbraad.excalidraw;

import android.Manifest;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.provider.Settings;
import android.view.View;
import android.widget.Button;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import androidx.documentfile.provider.DocumentFile;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

public class MainActivity extends AppCompatActivity {

    private RecyclerView recyclerView;
    private TextView tvCurrentPath;
    private TextView tvEmpty;
    private android.widget.LinearLayout topBar;
    private FileListAdapter adapter;
    private List<File> files = new ArrayList<>();
    private File currentFolder = null;
    private Uri currentFolderUri = null; // SAF URI for selected folder
    private java.util.HashMap<String, Uri> fileUriMap = new java.util.HashMap<>(); // Maps filename -> content URI for SAF files

    private ActivityResultLauncher<Intent> folderPickerLauncher;
    private static final int PERMISSION_REQUEST_CODE = 100;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        recyclerView  = findViewById(R.id.recycler_files);
        tvCurrentPath = findViewById(R.id.tv_current_path);
        tvEmpty       = findViewById(R.id.tv_empty);
        topBar        = findViewById(R.id.top_bar);
        Button btnBrowse = findViewById(R.id.btn_browse);
        Button btnNewDrawing = findViewById(R.id.btn_new_drawing);
        Button btnSelectFolder = findViewById(R.id.btn_select_folder);

        recyclerView.setLayoutManager(new LinearLayoutManager(this));
        adapter = new FileListAdapter(files, this::openExcalidrawFile);
        adapter.setDeleteListener((file, position) -> {
            files.remove(position);
            fileUriMap.remove(file.getName());
            adapter.notifyItemRemoved(position);
            updateEmptyState();
        });
        recyclerView.setAdapter(adapter);

        // Folder picker launcher (Storage Access Framework)
        folderPickerLauncher = registerForActivityResult(
            new ActivityResultContracts.StartActivityForResult(),
            result -> {
                if (result.getResultCode() == RESULT_OK && result.getData() != null) {
                    Uri uri = result.getData().getData();
                    if (uri != null) {
                        handleFolderSelection(uri);
                    }
                }
            }
        );

        btnNewDrawing.setOnClickListener(v -> createNewDrawing());

        btnBrowse.setOnClickListener(v -> {
            if (checkPermissions()) {
                refreshFileList();
            } else {
                Toast.makeText(this, "Storage permission required. Redirecting to Settings...", Toast.LENGTH_LONG).show();
                requestPermissions();
            }
        });

        btnSelectFolder.setOnClickListener(v -> {
            if (checkPermissions()) {
                openFolderPicker();
            } else {
                Toast.makeText(this, "Storage permission required. Redirecting to Settings...", Toast.LENGTH_LONG).show();
                requestPermissions();
            }
        });

        // Auto-scan on startup if we have permissions
        new android.os.Handler().postDelayed(() -> {
            if (checkPermissions()) {
                // Check if we have a saved folder URI
                String savedUri = getSharedPreferences("excalidraw", MODE_PRIVATE)
                    .getString("folder_uri", null);

                if (savedUri != null) {
                    // Restore saved folder
                    Uri uri = Uri.parse(savedUri);
                    currentFolderUri = uri;
                    String convertedPath = convertTreeUriToPath(uri);
                    if (convertedPath != null) {
                        currentFolder = new File(convertedPath);
                    }
                    scanForFilesViaSAF(uri);
                } else {
                    // No saved folder, browse Downloads
                    browseFolder();
                }
            } else {
                tvCurrentPath.setText("Tap 'Select Folder' to choose a folder");
            }
        }, 500);

        // Check intent for file opening
        handleIntent(getIntent());
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        handleIntent(intent);
    }

    private void handleIntent(Intent intent) {
        if (Intent.ACTION_VIEW.equals(intent.getAction()) && intent.getData() != null) {
            Uri uri = intent.getData();
            openExcalidrawFile(new File(uri.getPath()));
        }
    }

    private boolean checkPermissions() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            boolean hasPermission = Environment.isExternalStorageManager();
            android.util.Log.d("MainActivity", "Android 11+ permission check: " + hasPermission);
            return hasPermission;
        } else {
            int read = ContextCompat.checkSelfPermission(this, Manifest.permission.READ_EXTERNAL_STORAGE);
            int write = ContextCompat.checkSelfPermission(this, Manifest.permission.WRITE_EXTERNAL_STORAGE);
            boolean hasPermission = read == PackageManager.PERMISSION_GRANTED && write == PackageManager.PERMISSION_GRANTED;
            android.util.Log.d("MainActivity", "Android 10- permission check: READ=" + (read == PackageManager.PERMISSION_GRANTED) + " WRITE=" + (write == PackageManager.PERMISSION_GRANTED));
            return hasPermission;
        }
    }

    private void requestPermissions() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            try {
                Intent intent = new Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION);
                intent.setData(Uri.parse("package:" + getPackageName()));
                startActivity(intent);
            } catch (Exception e) {
                Intent intent = new Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION);
                startActivity(intent);
            }
        } else {
            ActivityCompat.requestPermissions(this,
                new String[]{
                    Manifest.permission.READ_EXTERNAL_STORAGE,
                    Manifest.permission.WRITE_EXTERNAL_STORAGE
                },
                PERMISSION_REQUEST_CODE);
        }
    }

    private void browseFolder() {
        // Try multiple possible locations for Downloads folder
        File downloadsFolder = null;

        android.util.Log.d("MainActivity", "=== Searching for Downloads folder ===");
        android.util.Log.d("MainActivity", "External storage dir: " + Environment.getExternalStorageDirectory().getAbsolutePath());
        android.util.Log.d("MainActivity", "External storage state: " + Environment.getExternalStorageState());

        // For Android 11+, use app-specific external storage (no permissions needed)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            // Try app-specific external storage first (guaranteed access)
            File appFolder = new File(getExternalFilesDir(null), "Drawings");
            if (!appFolder.exists()) {
                appFolder.mkdirs();
            }

            android.util.Log.d("MainActivity", "App-specific folder: " + appFolder.getAbsolutePath() + " exists=" + appFolder.exists());

            if (appFolder.exists()) {
                downloadsFolder = appFolder;
            }
        }

        // If not Android 11+ or app folder failed, try Downloads
        // NOTE: Don't check canRead() - it returns false with scoped storage but listFiles() still works
        if (downloadsFolder == null) {
            File primaryDownloads = new File(Environment.getExternalStorageDirectory(), "Download");
            android.util.Log.d("MainActivity", "Trying: " + primaryDownloads.getAbsolutePath() + " exists=" + primaryDownloads.exists());

            if (primaryDownloads.exists()) {
                downloadsFolder = primaryDownloads;
            } else {
                // Try alternative path
                primaryDownloads = new File("/storage/emulated/0/Download");
                android.util.Log.d("MainActivity", "Trying: " + primaryDownloads.getAbsolutePath() + " exists=" + primaryDownloads.exists());

                if (primaryDownloads.exists()) {
                    downloadsFolder = primaryDownloads;
                } else {
                    // Try Downloads with 's'
                    primaryDownloads = new File(Environment.getExternalStorageDirectory(), "Downloads");
                    android.util.Log.d("MainActivity", "Trying: " + primaryDownloads.getAbsolutePath() + " exists=" + primaryDownloads.exists());

                    if (primaryDownloads.exists()) {
                        downloadsFolder = primaryDownloads;
                    } else {
                        // Fallback to app-specific storage
                        downloadsFolder = new File(getExternalFilesDir(null), "Drawings");
                        if (!downloadsFolder.exists()) {
                            downloadsFolder.mkdirs();
                        }
                        android.util.Log.d("MainActivity", "Using app-specific fallback: " + downloadsFolder.getAbsolutePath());
                    }
                }
            }
        }

        if (downloadsFolder != null) {
            android.util.Log.d("MainActivity", "Selected folder: " + downloadsFolder.getAbsolutePath());
        }

        scanForFiles(downloadsFolder);
    }

    private void scanForFilesViaSAF(Uri treeUri) {
        files.clear();
        fileUriMap.clear(); // Clear URI map
        android.util.Log.d("MainActivity", "Scanning via SAF: " + treeUri);

        // Use DocumentFile - the CORRECT way to browse SAF folders!
        DocumentFile pickedDir = DocumentFile.fromTreeUri(this, treeUri);

        if (pickedDir == null || !pickedDir.exists()) {
            tvCurrentPath.setText("Cannot access folder");
            updateEmptyState();
            return;
        }

        if (!pickedDir.isDirectory()) {
            tvCurrentPath.setText("Not a directory");
            updateEmptyState();
            return;
        }

        tvCurrentPath.setText("Scanning: " + pickedDir.getName());

        DocumentFile[] docFiles = pickedDir.listFiles();
        android.util.Log.d("MainActivity", "=== SAF FILES FOUND: " + docFiles.length + " ===");

        for (DocumentFile docFile : docFiles) {
            String name = docFile.getName();
            boolean isFile = docFile.isFile();

            android.util.Log.d("MainActivity", "  " + name + " (isFile: " + isFile + ")");

            if (isFile && name != null && (name.endsWith(".excalidraw") || name.endsWith(".whiteboard"))) {
                // Use currentFolder path + filename (simpler and always works!)
                if (currentFolder != null) {
                    File file = new File(currentFolder, name);
                    files.add(file);

                    // CRITICAL: Store the content URI for this file so ExcalidrawActivity can use ContentResolver!
                    fileUriMap.put(name, docFile.getUri());
                    android.util.Log.d("MainActivity", "  ✓ ADDED: " + name + " URI: " + docFile.getUri());
                } else {
                    android.util.Log.w("MainActivity", "  currentFolder is null, cannot add file");
                }
            }
        }
        android.util.Log.d("MainActivity", "=== END SAF FILE LIST ===");

        adapter.setUriMap(fileUriMap);
        adapter.notifyDataSetChanged();
        updateEmptyState();

        if (files.isEmpty()) {
            tvCurrentPath.setText("No .excalidraw files");
        } else {
            tvCurrentPath.setText("Found " + files.size() + " file(s)");
        }
    }

    private String convertDocumentFileToPath(DocumentFile docFile) {
        // Try to get real file path from DocumentFile URI
        Uri uri = docFile.getUri();
        String uriString = uri.toString();

        if (uriString.contains("/document/primary:")) {
            String path = uriString.substring(uriString.indexOf("/document/primary:") + 18);
            path = Uri.decode(path);
            return Environment.getExternalStorageDirectory().getAbsolutePath() + "/" + path;
        }

        return null;
    }

    private void scanForFiles(File folder) {
        files.clear();
        fileUriMap.clear(); // Clear URI map (not using SAF)

        // Debug: Show what we're trying to scan
        String debugPath = folder != null ? folder.getAbsolutePath() : "null";
        android.util.Log.d("MainActivity", "Scanning: " + debugPath);

        if (folder == null || !folder.exists()) {
            tvCurrentPath.setText("Folder not found");
            updateEmptyState();
            return;
        }

        if (!folder.isDirectory()) {
            tvCurrentPath.setText("Not a directory");
            updateEmptyState();
            return;
        }

        // NOTE: Don't check canRead() - it returns false even with permission granted
        // due to scoped storage behavior. Just try to list files.

        tvCurrentPath.setText("Scanning: " + folder.getAbsolutePath());

        File[] fileArray = folder.listFiles();

        // Debug: Log what we found
        android.util.Log.d("MainActivity", "Files found: " + (fileArray != null ? fileArray.length : 0));

        if (fileArray != null) {
            for (File file : fileArray) {
                String name = file.getName();
                if (file.isFile() && (name.endsWith(".excalidraw") || name.endsWith(".whiteboard"))) {
                    files.add(file);
                    android.util.Log.d("MainActivity", "  Added: " + file.getName());
                }
            }
        } else {
            tvCurrentPath.setText("Tap 'Select Folder' to browse");
            android.util.Log.w("MainActivity", "listFiles() returned null");
        }

        adapter.setUriMap(fileUriMap);
        adapter.notifyDataSetChanged();
        updateEmptyState();

        // Show result
        if (files.isEmpty()) {
            tvCurrentPath.setText("No .excalidraw files");
        } else {
            tvCurrentPath.setText("Found " + files.size() + " file(s)");
        }
    }

    private void updateEmptyState() {
        if (files.isEmpty()) {
            recyclerView.setVisibility(View.GONE);
            tvEmpty.setVisibility(View.VISIBLE);
        } else {
            recyclerView.setVisibility(View.VISIBLE);
            tvEmpty.setVisibility(View.GONE);
        }
    }

    private void createNewDrawing() {
        // User MUST select a folder first - NO automatic fallback to app storage!
        if (currentFolderUri == null) {
            Toast.makeText(this, "Select a folder first", Toast.LENGTH_LONG).show();
            return;
        }

        // Create a new file with timestamp
        String timestamp = new java.text.SimpleDateFormat("yyyyMMdd_HHmmss", java.util.Locale.getDefault())
            .format(new java.util.Date());
        String filename = "drawing_" + timestamp + ".excalidraw";
        boolean dark = "dark".equals(
                getSharedPreferences("excalidraw", MODE_PRIVATE).getString("theme", "light"));
        String emptyDrawing = "{\"type\":\"excalidraw\",\"version\":1,\"source\":\"android\",\"elements\":[],"
                + "\"appState\":{\"viewBackgroundColor\":\""
                + (dark ? "#1e1e2e" : "#ffffff")
                + "\",\"theme\":\""
                + (dark ? "dark" : "light")
                + "\"}}";

        try {
            android.util.Log.d("MainActivity", "Creating new file in SAF folder: " + currentFolderUri);

            DocumentFile pickedDir = DocumentFile.fromTreeUri(this, currentFolderUri);
            if (pickedDir != null && pickedDir.exists()) {
                // Create new file (use generic MIME type to prevent auto .json extension)
                DocumentFile newDocFile = pickedDir.createFile("application/octet-stream", filename);
                if (newDocFile != null) {
                    Uri newFileUri = newDocFile.getUri();

                    // Write empty drawing via ContentResolver
                    java.io.OutputStream os = getContentResolver().openOutputStream(newFileUri, "wt");
                    if (os != null) {
                        os.write(emptyDrawing.getBytes(java.nio.charset.StandardCharsets.UTF_8));
                        os.close();

                        android.util.Log.d("MainActivity", "Created file via SAF: " + filename);
                        Toast.makeText(this, "Created: " + filename, Toast.LENGTH_SHORT).show();

                        // CRITICAL: Add the new file's URI to the map so it can be opened!
                        fileUriMap.put(filename, newFileUri);

                        // Open the new drawing (do this BEFORE refreshing to preserve URI!)
                        File file = new File(currentFolder, filename);
                        openExcalidrawFile(file);

                        // Refresh file list (will happen in onResume when we return from editor)
                        // Don't refresh now - it would clear the URI map!
                        return;
                    }
                }
            }

            Toast.makeText(this, "Failed to create file", Toast.LENGTH_SHORT).show();
        } catch (Exception e) {
            Toast.makeText(this, "Failed: " + e.getMessage(), Toast.LENGTH_SHORT).show();
            android.util.Log.e("MainActivity", "Failed to create file", e);
        }
    }

    private void openFolderPicker() {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE);
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION |
                       Intent.FLAG_GRANT_WRITE_URI_PERMISSION |
                       Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);
        folderPickerLauncher.launch(intent);
    }

    private void handleFolderSelection(Uri treeUri) {
        // Take persistable permission
        try {
            getContentResolver().takePersistableUriPermission(treeUri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
            android.util.Log.d("MainActivity", "Took persistable permission for: " + treeUri);
        } catch (Exception e) {
            android.util.Log.w("MainActivity", "Could not take persistable permission", e);
        }

        // Store the URI
        currentFolderUri = treeUri;

        // Try to convert URI to File path for listing
        String path = treeUri.getPath();
        android.util.Log.d("MainActivity", "Selected URI: " + treeUri + " path: " + path);

        // SAVE the URI so it persists across app restarts
        getSharedPreferences("excalidraw", MODE_PRIVATE)
            .edit()
            .putString("folder_uri", treeUri.toString())
            .apply();

        // Convert to File path FIRST (needed for adding files)
        String convertedPath = convertTreeUriToPath(treeUri);
        if (convertedPath != null) {
            currentFolder = new File(convertedPath);
            android.util.Log.d("MainActivity", "Converted to File path: " + currentFolder.getAbsolutePath());
        }

        // Use DocumentFile to browse SAF-selected folders (this is the CORRECT way!)
        android.util.Log.d("MainActivity", "Using SAF URI: " + treeUri);
        scanForFilesViaSAF(treeUri);
    }

    private String convertTreeUriToPath(Uri treeUri) {
        // Convert document tree URI to file path (same as regroove)
        String uriString = treeUri.toString();
        android.util.Log.d("MainActivity", "Converting URI: " + uriString);

        // Handle primary storage
        if (uriString.contains("/tree/primary:") || uriString.contains("/tree/primary%3A")) {
            int startIndex;
            if (uriString.contains("/tree/primary:")) {
                startIndex = uriString.lastIndexOf("/tree/primary:") + 14;
            } else {
                startIndex = uriString.lastIndexOf("/tree/primary%3A") + 16;
            }

            String relativePath = uriString.substring(startIndex);
            relativePath = Uri.decode(relativePath);

            String fullPath = Environment.getExternalStorageDirectory().getAbsolutePath() + "/" + relativePath;
            android.util.Log.d("MainActivity", "Converted to path: " + fullPath);
            return fullPath;
        }

        // Handle raw: URIs and other formats
        if (uriString.contains("/tree/")) {
            int treeIndex = uriString.indexOf("/tree/");
            String afterTree = uriString.substring(treeIndex + 6);
            afterTree = Uri.decode(afterTree);

            String[] parts = afterTree.split(":", 2);
            if (parts.length >= 1) {
                String storageId = parts[0];
                String relativePath = parts.length > 1 ? parts[1] : "";

                // Handle raw: prefix
                if (storageId.equals("raw")) {
                    return relativePath;
                }

                // Try /storage/STORAGE_ID format
                String fullPath = "/storage/" + storageId;
                if (!relativePath.isEmpty()) {
                    fullPath += "/" + relativePath;
                }

                android.util.Log.d("MainActivity", "Converted to path: " + fullPath);
                return fullPath;
            }
        }

        android.util.Log.w("MainActivity", "Unhandled URI format: " + uriString);
        return null;
    }

    private void openExcalidrawFile(File file) {
        Intent intent = new Intent(this, ExcalidrawActivity.class);
        intent.putExtra("file_path", file.getAbsolutePath());

        // CRITICAL: Pass content URI if this is a SAF file
        // ExcalidrawActivity MUST use ContentResolver for SAF files, NOT FileInputStream!
        Uri contentUri = fileUriMap.get(file.getName());
        if (contentUri != null) {
            intent.putExtra("content_uri", contentUri.toString());
            android.util.Log.d("MainActivity", "Opening SAF file with URI: " + contentUri);
        } else {
            android.util.Log.d("MainActivity", "Opening regular file (no URI): " + file.getAbsolutePath());
        }

        startActivity(intent);
    }

    private void applyTheme() {
        boolean dark = "dark".equals(
                getSharedPreferences("excalidraw", MODE_PRIVATE).getString("theme", "light"));

        // Status bar
        getWindow().setStatusBarColor(dark ? 0xFF3D3B8E : 0xFF5753C5);

        // Top bar — slightly muted purple in dark mode, bright in light
        topBar.setBackgroundColor(dark ? 0xFF3D3B8E : 0xFF6965DB);

        // Content background
        recyclerView.setBackgroundColor(dark ? 0xFF1E1E2E : 0xFFFFFFFF);
        findViewById(android.R.id.content).setBackgroundColor(dark ? 0xFF1E1E2E : 0xFFFFFFFF);

        // Path bar
        tvCurrentPath.setBackgroundColor(dark ? 0xFF252535 : 0xFFF5F5F5);
        tvCurrentPath.setTextColor(dark ? 0xFF9999BB : 0xFF666666);

        // Empty state
        tvEmpty.setTextColor(dark ? 0xFF777788 : 0xFF999999);

        // File list items
        adapter.setDarkMode(dark);
        adapter.notifyDataSetChanged();
    }

    private void refreshFileList() {
        if (currentFolderUri != null) {
            scanForFilesViaSAF(currentFolderUri);
        } else if (currentFolder != null) {
            scanForFiles(currentFolder);
        } else {
            browseFolder();
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        applyTheme();
        if (checkPermissions()) {
            refreshFileList();
        } else {
            tvCurrentPath.setText("Grant storage permission to browse files");
        }
    }
}
