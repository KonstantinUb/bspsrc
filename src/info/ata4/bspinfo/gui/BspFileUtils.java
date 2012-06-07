/*
 ** 2012 June 7
 **
 ** The author disclaims copyright to this source code.  In place of
 ** a legal notice, here is a blessing:
 **    May you do good and not evil.
 **    May you find forgiveness for yourself and forgive others.
 **    May you share freely, never taking more than you give.
 */
package info.ata4.bspinfo.gui;

import info.ata4.bsplib.BspFile;
import info.ata4.bsplib.lump.GameLump;
import info.ata4.bsplib.lump.Lump;
import info.ata4.bsplib.lump.LumpType;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;
import org.apache.commons.compress.archivers.zip.ZipArchiveEntry;
import org.apache.commons.compress.archivers.zip.ZipArchiveInputStream;
import org.apache.commons.io.FileUtils;
import org.apache.commons.io.IOUtils;
import org.apache.commons.io.input.CloseShieldInputStream;

/**
 *
 * @author Nico Bergemann <barracuda415 at yahoo.de>
 */
public class BspFileUtils {
    
    private static final Logger L = Logger.getLogger(BspFileUtils.class.getName());
    
    public static int extractLumps(BspFile bspFile, File dest) throws IOException {
        FileUtils.forceMkdir(dest);

        List<Lump> lumps = bspFile.getLumps();
        int files = 0;

        for (Lump lump : lumps) {
            if (lump.getType() == LumpType.LUMP_UNKNOWN) {
                continue;
            }

            String fileName = String.format("%02d_%s.bin", lump.getIndex(),
                    lump.getName());
            File lumpFile = new File(dest, fileName);

            L.log(Level.INFO, "Extracting {0}", lump);

            try {
                InputStream is = lump.getInputStream();
                FileUtils.copyInputStreamToFile(is, lumpFile);
                files++;
            } catch (IOException ex) {
                throw new BspFileException("Can't extract lump", ex);
            }
        }

        File gameLumpsDir = new File(dest, "game");
        gameLumpsDir.mkdir();

        List<GameLump> gameLumps = bspFile.getGameLumps();

        for (GameLump lump : gameLumps) {
            String fileName = String.format("%s_v%d.bin", lump.getName(), lump.getVersion());
            File lumpFile = new File(gameLumpsDir, fileName);

            L.log(Level.INFO, "Extracting {0}", lump);

            try {
                InputStream is = lump.getInputStream();
                FileUtils.copyInputStreamToFile(is, lumpFile);
                files++;
            } catch (IOException ex) {
                throw new BspFileException("Can't extract lump", ex);
            }
        }
        
        return files;
    }
    
    public static int extractPak(BspFile bspFile, File dest) throws IOException {
        FileUtils.forceMkdir(dest);

        ZipArchiveInputStream zis = null;
        int files = 0;

        try {
            Lump pakLump = bspFile.getLump(LumpType.LUMP_PAKFILE);
            zis = new ZipArchiveInputStream(pakLump.getInputStream());

            try {
                for (ZipArchiveEntry ze; (ze = zis.getNextZipEntry()) != null; files++) {
                    File entryFile = new File(dest, ze.getName());
                    L.log(Level.INFO, "Extracting {0}", ze.getName());

                    try {
                        InputStream cszis = new CloseShieldInputStream(zis);
                        FileUtils.copyInputStreamToFile(cszis, entryFile);
                    } catch (IOException ex) {
                        throw new BspFileException("Can't extract pak entry", ex);
                    }
                }
            } catch (IOException ex) {
                throw new BspFileException("Can't read pak", ex);
            }
        } finally {
            IOUtils.closeQuietly(zis);
        }

        return files;
    }
}