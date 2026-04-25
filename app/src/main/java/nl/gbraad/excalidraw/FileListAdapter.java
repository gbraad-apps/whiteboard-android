package nl.gbraad.excalidraw;

import android.app.AlertDialog;
import android.content.Context;
import android.net.Uri;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.documentfile.provider.DocumentFile;
import androidx.recyclerview.widget.RecyclerView;

import java.io.File;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class FileListAdapter extends RecyclerView.Adapter<FileListAdapter.ViewHolder> {

    public interface OnFileClickListener {
        void onFileClick(File file);
    }

    public interface OnFileDeleteListener {
        void onFileDelete(File file, int position);
    }

    private final List<File> files;
    private final OnFileClickListener openListener;
    private OnFileDeleteListener deleteListener;
    private Map<String, Uri> uriMap = new HashMap<>();
    private boolean darkMode = false;

    public FileListAdapter(List<File> files, OnFileClickListener openListener) {
        this.files = files;
        this.openListener = openListener;
    }

    public void setUriMap(Map<String, Uri> map)          { this.uriMap = map; }
    public void setDeleteListener(OnFileDeleteListener l) { this.deleteListener = l; }
    public void setDarkMode(boolean dark)                 { this.darkMode = dark; }

    @NonNull
    @Override
    public ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View v = LayoutInflater.from(parent.getContext())
                .inflate(R.layout.item_file, parent, false);
        return new ViewHolder(v);
    }

    @Override
    public void onBindViewHolder(@NonNull ViewHolder holder, int position) {
        holder.bind(files.get(position), position);
    }

    @Override
    public int getItemCount() { return files.size(); }

    class ViewHolder extends RecyclerView.ViewHolder {
        TextView tvFilename, tvFilesize;
        Button btnDelete, btnOpen;
        private boolean deleteArmed = false;

        ViewHolder(@NonNull View itemView) {
            super(itemView);
            tvFilename = itemView.findViewById(R.id.tv_filename);
            tvFilesize = itemView.findViewById(R.id.tv_filesize);
            btnDelete  = itemView.findViewById(R.id.btn_delete);
            btnOpen    = itemView.findViewById(R.id.btn_open);
        }

        void bind(File file, int position) {
            deleteArmed = false;
            btnDelete.setText("✕");
            resetDeleteButton();

            itemView.setBackgroundColor(darkMode ? 0xFF1E1E2E : 0xFFFFFFFF);
            tvFilename.setTextColor(darkMode ? 0xFFDDDDEE : 0xFF000000);
            tvFilesize.setTextColor(darkMode ? 0xFF8888AA : 0xFF666666);

            tvFilename.setText(file.getName());

            // File size — try direct length first, fall back via DocumentFile for SAF files
            long bytes = file.length();
            if (bytes == 0) {
                Uri uri = uriMap.get(file.getName());
                if (uri != null) {
                    DocumentFile doc = DocumentFile.fromSingleUri(itemView.getContext(), uri);
                    if (doc != null) bytes = doc.length();
                }
            }
            long sizeMB = bytes / (1024 * 1024);
            long sizeKB = bytes / 1024;
            tvFilesize.setText(bytes == 0 ? "—" : sizeMB > 0 ? sizeMB + " MB" : sizeKB + " KB");

            // Tap filename → rename dialog
            tvFilename.setOnClickListener(v -> showRenameDialog(v.getContext(), file));

            // × delete — arms on first tap, confirms on second
            btnDelete.setOnClickListener(v -> {
                if (!deleteArmed) {
                    deleteArmed = true;
                    btnDelete.setText("?");
                    btnDelete.setBackgroundTintList(
                            android.content.res.ColorStateList.valueOf(0xFFCF1A37));
                    btnDelete.postDelayed(() -> {
                        if (deleteArmed) {
                            deleteArmed = false;
                            btnDelete.setText("✕");
                            resetDeleteButton();
                        }
                    }, 3000);
                } else {
                    deleteArmed = false;
                    Uri uri = uriMap.get(file.getName());
                    if (uri != null) {
                        DocumentFile doc = DocumentFile.fromSingleUri(v.getContext(), uri);
                        if (doc != null) doc.delete();
                    } else {
                        file.delete();
                    }
                    if (deleteListener != null) deleteListener.onFileDelete(file, getAdapterPosition());
                }
            });

            // → open drawing
            btnOpen.setOnClickListener(v -> {
                if (openListener != null) openListener.onFileClick(file);
            });
        }

        private void resetDeleteButton() {
            int tint = darkMode ? 0xFF444455 : 0xFFDDDDDD;
            int textColor = darkMode ? 0xFFAAAAAA : 0xFF555555;
            btnDelete.setBackgroundTintList(android.content.res.ColorStateList.valueOf(tint));
            btnDelete.setTextColor(textColor);
        }

        private void showRenameDialog(Context ctx, File file) {
            String current = file.getName();

            EditText input = new EditText(ctx);
            input.setText(current);
            // Select just the base name so the extension is visible but cursor lands before it
            int dotIndex = current.lastIndexOf('.');
            input.setSelection(0, dotIndex > 0 ? dotIndex : current.length());

            int style = darkMode ? android.R.style.Theme_DeviceDefault_Dialog_Alert
                                 : android.R.style.Theme_DeviceDefault_Light_Dialog_Alert;
            new AlertDialog.Builder(ctx, style)
                    .setTitle("Rename")
                    .setView(input)
                    .setPositiveButton("Rename", (d, w) -> {
                        String newName = input.getText().toString().trim();
                        if (newName.isEmpty() || newName.equals(current)) return;
                        Uri uri = uriMap.get(current);
                        if (uri != null) {
                            DocumentFile doc = DocumentFile.fromSingleUri(ctx, uri);
                            if (doc != null) doc.renameTo(newName);
                        } else {
                            file.renameTo(new File(file.getParent(), newName));
                        }
                        tvFilename.setText(newName);
                    })
                    .setNegativeButton("Cancel", null)
                    .show();
        }
    }
}
