package fr.ivorama.cognitive.agent;

import java.io.IOException;
import java.nio.file.FileSystemException;
import java.nio.file.Files;
import java.nio.file.Path;

final class SymlinkFixture {
    private SymlinkFixture() {}

    static boolean createIfSupported(Path link, Path target) throws IOException {
        try {
            Files.createSymbolicLink(link, target);
            return true;
        } catch (UnsupportedOperationException unavailable) {
            System.out.println("Vérification du lien symbolique omise : système de fichiers incompatible.");
            return false;
        } catch (FileSystemException unavailable) {
            if (!System.getProperty("os.name").startsWith("Windows")) throw unavailable;
            System.out.println("Vérification du lien symbolique omise : " + unavailable.getReason());
            return false;
        }
    }
}
