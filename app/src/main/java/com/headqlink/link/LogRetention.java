package com.headqlink.link;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Retención común de los registros (qdauto §7.1): en un directorio, de los ficheros que empiezan por un prefijo, se
 * conservan los más nuevos hasta un número máximo de ficheros y de bytes en total. Sin Android (se prueba en la JVM).
 */
final class LogRetention {
    private LogRetention() {
    }

    /**
     * Borra los ficheros más viejos de dir que empiezan por prefix hasta dejar como mucho maxFiles y maxBytes.
     * keep (puede ser null) no se borra nunca (el fichero en uso). Devuelve los borrados.
     */
    static List<File> prune(File dir, String prefix, int maxFiles, long maxBytes, File keep) {
        List<File> deleted = new ArrayList<>();
        if (dir == null) return deleted;
        File[] files = dir.listFiles(f -> f.isFile() && f.getName().startsWith(prefix));
        if (files == null || files.length == 0) return deleted;
        // Del más nuevo al más viejo (por fecha y, a igualdad, por nombre: llevan la hora en el nombre).
        Arrays.sort(files, (a, b) -> {
            int c = Long.compare(b.lastModified(), a.lastModified());
            return c != 0 ? c : b.getName().compareTo(a.getName());
        });
        long total = 0;
        int kept = 0;
        // Se conserva una ventana continua de los más nuevos: en cuanto uno no cabe, se borran también los anteriores.
        boolean full = false;
        for (File f : files) {
            boolean current = keep != null && f.getAbsoluteFile().equals(keep.getAbsoluteFile());
            long len = f.length();
            if (!full && kept < maxFiles && total + len <= maxBytes) {
                kept++;
                total += len;
                continue;
            }
            full = true;
            if (current) continue;
            //noinspection ResultOfMethodCallIgnored
            if (f.delete()) deleted.add(f);
        }
        return deleted;
    }

    static List<File> prune(File dir, String prefix, int maxFiles, long maxBytes) {
        return prune(dir, prefix, maxFiles, maxBytes, null);
    }
}
