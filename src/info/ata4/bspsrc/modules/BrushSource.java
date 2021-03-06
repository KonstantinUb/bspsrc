/*
** 2011 April 5
**
** The author disclaims copyright to this source code.  In place of
** a legal notice, here is a blessing:
**    May you do good and not evil.
**    May you find forgiveness for yourself and forgive others.
**    May you share freely, never taking more than you give.
*/

package info.ata4.bspsrc.modules;

import info.ata4.bsplib.BspFileReader;
import info.ata4.bsplib.struct.DBrush;
import info.ata4.bsplib.struct.DBrushSide;
import info.ata4.bsplib.struct.DModel;
import info.ata4.bsplib.vector.Vector3f;
import info.ata4.bspsrc.BspSourceConfig;
import info.ata4.bspsrc.Texture;
import info.ata4.bspsrc.VmfWriter;
import info.ata4.bspsrc.util.TreeLimit;
import info.ata4.bspsrc.util.Winding;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Decompiling module to rebuild brushes from the LUMP_BRUSHES and LUMP_BRUSHSIDES lumps.
 *
 * Based on Vmex.vmfbrushes() and Vmex.writebrush()
 * 
 * @author Nico Bergemann <barracuda415 at yahoo.de>
 */
public class BrushSource extends ModuleDecompile {

    // logger
    private static final Logger L = Logger.getLogger(BrushSource.class.getName());
    
    // parent module
    private final BspDecompiler parent;
    
    // sub-modules
    private final BspSourceConfig config;
    private final TextureSource texsrc;
    private final BspProtection bspprot;
    
    // additional model data
    private List<DBrushModel> models = new ArrayList<DBrushModel>();
    
    // amount of world brushes
    private int worldbrushes = 0;
    
    // brush side ID mappings
    Map<Integer, Integer> brushSideToID = new HashMap<Integer, Integer>();
    Map<Integer, Integer> brushIndexToID = new HashMap<Integer, Integer>();

    public BrushSource(BspFileReader reader, VmfWriter writer, BspSourceConfig config,
            BspDecompiler parent, TextureSource texsrc, BspProtection bspprot) {
        super(reader, writer);
        this.config = config;
        this.parent = parent;
        this.texsrc = texsrc;
        this.bspprot = bspprot;

        assignBrushes();
    }

    /**
     * Walks the map's BSP tree to associate brushes with entities and to find
     * the index of the last worldbrush.
     */
    private void assignBrushes() {
        // walk the BSP tree
        // to from the headnode of each model
        // to calculate the minimum and maximum brush in the tree
        // much simpler than the guessing method
        // plus this recovers null-faced brushes
        
        TreeLimit tl = new TreeLimit(bsp);
        
        // walk model 0 (worldspawn model)
        tl.walk(0);

        L.fine("Walked worldspawn tree");

        worldbrushes = tl.getMaxBrushLeaf() + 1;

        for (DModel model : bsp.models) {
            tl.reset();
            tl.walk(model.headnode);

            DBrushModel bmodel = new DBrushModel();
            bmodel.fstbrush = tl.getMinBrushLeaf();
            bmodel.numbrush = tl.getMaxBrushLeaf() - tl.getMinBrushLeaf() + 1;
            models.add(bmodel);
        }

        L.log(Level.FINE, "Largest worldbrush: {0}", worldbrushes);
    }

    /**
     * Writes all world brushes. Depending on the settings, some brushes may be
     * skipped so the entity decompiler can use them.
     */
    public void writeBrushes() {
        L.info("Writing brushes and planes");

        for (int i = 0; i < worldbrushes; i++) {
            DBrush brush = bsp.brushes.get(i);

            // skip details
            if (config.writeDetails && brush.isSolid() && brush.isDetail()) {
                continue;
            }

            // skip areaportals
            if (config.writeAreaportals && brush.isAreaportal()) {
                continue;
            }
            
            // NOTE: occluder brushes aren't worldbrushes

            writeBrush(i);
        }
    }
    
    public boolean writeBrush(int ibrush, Vector3f origin, Vector3f angles) {
        DBrush brush = bsp.brushes.get(ibrush);
        
        int brushID = parent.nextBrushID();
        
        // map brush index to ID
        brushIndexToID.put(ibrush, brushID);

        Map<Integer, Winding> validBrushSides = new HashMap<Integer, Winding>();

        // check and preprocess the brush sides before writing the brush
        for (int i = 0; i < brush.numside; i++) {
            int ibrushside = brush.fstside + i;
            DBrushSide brushSide = bsp.brushSides.get(ibrushside);
            
            if (brushSide.bevel) {
                continue;
            }

            try {
                Winding wind = Winding.fromSide(bsp, brush, i);
                
                // remove close vertices
                wind.removeDegenerated();
                
                // skip sides with no vertices
                if (wind.isEmpty()) {
                    throw new BrushSideException("no vertices");
                }
                
                // skip sides with too few vertices
                if (wind.size() < 3) {
                    throw new BrushSideException("less than 3 vertices");
                }
                
                // skip sides that are way too big
                if (wind.isHuge()) {
                    throw new BrushSideException("too big");
                }

                Vector3f[] plane = wind.buildPlane();

                Vector3f e1 = plane[0];
                Vector3f e2 = plane[1];
                Vector3f e3 = plane[2];

                if (!e1.isValid() || !e2.isValid() || !e3.isValid()) {
                    throw new BrushSideException("invalid plane");
                }

                // Check for duplicate plane points. All three plane points must
                // be unique or it isn't a valid plane.
                for (int p1 = 0; p1 < plane.length; p1++) {
                    for (int p2 = 0; p2 < plane.length; p2++) {
                        if (p1 == p2) {
                            continue;
                        }

                        Vector3f v1 = plane[p1];
                        Vector3f v2 = plane[p2];

                        if (v1.equals(v2)) {
                            throw new BrushSideException("duplicate plane point " + v1);
                        }
                    }
                }

                // rotate
                if (angles != null) {
                    wind.rotate(angles);
                }

                // translate to origin
                if (origin != null) {
                    wind.translate(origin);
                }

                // the brush side should be safe to write
                validBrushSides.put(ibrushside, wind);
            } catch (BrushSideException ex) {
                if (config.isDebug()) {
                    L.log(Level.WARNING, "Skipped side {0} of brush {1}: {2}",
                            new Object[]{i, ibrush, ex.getMessage()});

                }
            }
        }
        
        // all brush sides invalid = invalid brush
        if (validBrushSides.isEmpty()) {
            L.log(Level.WARNING, "Skipped empty brush {0}", ibrush);
            return false;
        } 
        
        // skip brushes with less than three sides, they can't be compiled and
        // may crash older Hammer builds
        if (validBrushSides.size() < 3) {
            L.log(Level.WARNING, "Skipped brush {0} with less than 3 sides", ibrush);
            return false;
        }
        
        // now write the brush
        writer.start("solid");
        writer.put("id", brushID);
        
        // write metadata for debugging
        if (config.isDebug()) {    
            writer.start("bspsrc_debug");
            writer.put("brush_index", ibrush);
            writer.put("brush_contents", brush.contents.toString());
            writer.end("bspsrc_debug");
        }

        // write valid sides only
        for (Map.Entry<Integer, Winding> entry : validBrushSides.entrySet()) {
            int ibrushside = entry.getKey();
            Winding wind = entry.getValue();
            writeSide(ibrushside, ibrush, wind, origin, angles);
        }

        // don't add visgroup metadata here if this is a protector detail brush
        if (!brush.isDetail() && bspprot.isProtectedBrush(brush)) {
            parent.writeMetaVisgroup("VMEX protector brushes");
        }

        writer.end("solid");
        
        return true;
    }
    
    public boolean writeBrush(int ibrush) {
        return writeBrush(ibrush, null, null);
    }

    private boolean writeSide(int ibrushside, int ibrush, Winding wind, Vector3f origin, Vector3f angles) {
        DBrushSide brushSide = bsp.brushSides.get(ibrushside);
        
        // don't output bevel faces - they lead to bad brushes
        if (brushSide.bevel) {
            return false;
        }
        
        // calculate plane vectors
        Vector3f[] plane = wind.buildPlane();
        
        Vector3f e1 = plane[0];
        Vector3f e2 = plane[1];
        Vector3f e3 = plane[2];
        
        // calculate plane normal
        // NOTE: the plane normal from the BSP could be invalid if the brush was
        //       rotated! better re-calculate it every time.
        Vector3f ev12 = e2.sub(e1);
        Vector3f ev13 = e3.sub(e1);
        Vector3f normal = ev12.cross(ev13).normalize();
        
        Texture texture = texsrc.getTexture(brushSide.texinfo, origin, angles, normal);
        String origMaterial = null;
        
        // set face texture string
        if (!config.faceTexture.equals("")) {
            texture.setMaterial(config.faceTexture);
        } else if (config.fixToolTextures) {
            origMaterial = texsrc.fixToolTextures(texture, ibrush, ibrushside);
        }
        
        int sideID = parent.nextSideID();
        
        // add side id to cubemap side list
        if (texture.getData() != null) {
            texsrc.addBrushSideID(texture.getData().texname, sideID);
        }

        // map brush side index to brush side ID
        brushSideToID.put(ibrushside, sideID);

        writer.start("side");
        writer.put("id", sideID);
        
        // write metadata for debugging
        if (config.isDebug()) {
            writer.start("bspsrc_debug");
            writer.put("brushside_index", ibrushside);
            writer.put("normal", normal);
            writer.put("winding", wind.toString());
            
            if (origMaterial != null) {
                writer.put("original_material", origMaterial);
            }
            
            if (brushSide.texinfo != -1) {
                writer.put("texinfo_index", brushSide.texinfo);
                writer.put("texinfo_flags", bsp.texinfos.get(brushSide.texinfo).flags.toString());
            }
            writer.end("bspsrc_debug");
        }
        
        writer.put("plane", e1, e2, e3);
        writer.put("smoothing_groups", 0);
        writer.put(texture);

        writer.end("side");
        
        return true;
    }

    public boolean writeModel(int imodel, Vector3f origin, Vector3f angles) {
        DBrushModel bmodel;
        
        try {
            bmodel = models.get(imodel);
        } catch (IndexOutOfBoundsException ex) {
            L.log(Level.WARNING, "Invalid model index {0}", imodel);
            return false;
        }
        
        for (int i = 0; i < bmodel.numbrush; i++) {
            writeBrush(bmodel.fstbrush + i, origin, angles);
        }
        
        return true;
    }
    
    public boolean writeModel(int imodel) {
        return writeModel(imodel);
    }

    public int getWorldbrushes() {
        return worldbrushes;
    }
    
    private class DBrushModel {
        private int fstbrush;
        private int numbrush;
    }
    
    private class BrushSideException extends Exception {
        public BrushSideException(String message) {
            super(message);
        }
    }
}
