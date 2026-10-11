package com.fieldkit.app.report

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.content.FileProvider
import com.fieldkit.app.core.Fmt
import com.fieldkit.app.data.Session
import com.fieldkit.app.data.SessionStore
import java.io.File
import java.io.OutputStream

/**
 * Produces the two report formats and hands them to Android:
 *  - Storage Access Framework: the user picks where the file is written.
 *  - Share sheet: the file is exposed read-only through a FileProvider.
 * The app itself never transmits anything (it has no INTERNET permission).
 */
object Exporter {
    const val MIME_PDF = "application/pdf"
    const val MIME_TEXT = "text/plain"

    fun fileName(session: Session, extension: String): String {
        val safeName = session.name.replace(Regex("[^A-Za-z0-9_-]+"), "_").trim('_').take(40).ifEmpty { "session" }
        return "FieldKit_${safeName}_${Fmt.fileStamp(System.currentTimeMillis())}.$extension"
    }

    fun writeText(out: OutputStream, session: Session) {
        out.write(ReportText.build(session, System.currentTimeMillis()).toByteArray(Charsets.UTF_8))
    }

    fun writePdf(out: OutputStream, session: Session, store: SessionStore) {
        val images = session.snapshots.mapIndexedNotNull { i, snap ->
            val file = store.snapshotFile(session.id, snap.fileName)
            if (file.exists()) "Snapshot ${i + 1}: ${Fmt.dateTime(snap.timeMs)}  ${snap.caption}" to file else null
        }
        PdfReport.write(out, ReportText.build(session, System.currentTimeMillis()), images)
    }

    /** Writes to a location the user picked with the system file picker. */
    fun saveTo(context: Context, uri: Uri, pdf: Boolean, session: Session, store: SessionStore): Boolean = try {
        val stream = context.contentResolver.openOutputStream(uri)
        if (stream == null) {
            false
        } else {
            stream.use { if (pdf) writePdf(it, session, store) else writeText(it, session) }
            true
        }
    } catch (e: Exception) {
        false
    }

    /**
     * Writes the report into the app's cache and returns a share-sheet intent
     * for it, or null if the file could not be written. Call off the main
     * thread; start the returned intent on the main thread.
     */
    fun buildShareIntent(context: Context, pdf: Boolean, session: Session, store: SessionStore): Intent? = try {
        val folder = File(context.cacheDir, "exports")
        folder.mkdirs()
        folder.listFiles()?.forEach { it.delete() } // keep only the latest export
        val file = File(folder, fileName(session, if (pdf) "pdf" else "txt"))
        file.outputStream().use { if (pdf) writePdf(it, session, store) else writeText(it, session) }

        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
        val send = Intent(Intent.ACTION_SEND)
            .setType(if (pdf) MIME_PDF else MIME_TEXT)
            .putExtra(Intent.EXTRA_STREAM, uri)
            .putExtra(Intent.EXTRA_SUBJECT, "Field Kit report: ${session.name}")
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        Intent.createChooser(send, "Share report")
    } catch (e: Exception) {
        null
    }
}
