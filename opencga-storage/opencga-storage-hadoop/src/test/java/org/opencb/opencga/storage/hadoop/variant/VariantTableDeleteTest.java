package org.opencb.opencga.storage.hadoop.variant;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

import org.junit.Test;
import org.opencb.biodata.models.variant.Variant;
import org.opencb.biodata.models.variant.VariantSource;
import org.opencb.biodata.models.variant.avro.VariantAnnotation;
import org.opencb.opencga.storage.core.StorageManagerException;
import org.opencb.opencga.storage.core.StudyConfiguration;
import org.opencb.opencga.storage.core.variant.VariantStorageManagerTestUtils;

/**
 * @author Matthias Haimel mh719+git@cam.ac.uk
 *
 */
public class VariantTableDeleteTest extends HadoopVariantStorageManagerTestUtils {

    private VariantSource loadFile(String resource, StudyConfiguration studyConfiguration, Map<? extends String, ?> map) throws Exception {
        return VariantHbaseTestUtils.loadFile(getVariantStorageManager(), DB_NAME, outputUri, resource, studyConfiguration, map);
    }

    private void removeFile(String file, StudyConfiguration studyConfiguration, Map<? extends String, ?> map) throws Exception {
        Integer fileId = studyConfiguration.getFileIds().get(file);
        System.out.println(String.format("Use File ID %s for %s", fileId, file));
        VariantHbaseTestUtils.removeFile(getVariantStorageManager(), DB_NAME, outputUri, fileId, studyConfiguration, map);
    }

    @Test
    public void deleteTest() throws Exception {
        HadoopVariantStorageManager sm = getVariantStorageManager();
        StudyConfiguration studyConfiguration = VariantStorageManagerTestUtils.newStudyConfiguration();
        System.out.println("studyConfiguration = " + studyConfiguration);
        String studyName = Integer.toString(studyConfiguration.getStudyId());

        loadFile("s1.genome.vcf", studyConfiguration, Collections.emptyMap());
        Map<String, Variant> variants = buildVariantsIdx();
        assertFalse(variants.containsKey("1:10014:A:G"));

        assertTrue(variants.containsKey("1:10013:T:C"));
        assertEquals("0/1", variants.get("1:10013:T:C").getStudy(studyName).getSampleData("s1", "GT"));
        assertEquals(null, variants.get("1:10013:T:C").getStudy(studyName).getSampleData("s2", "GT"));

        loadFile("s2.genome.vcf", studyConfiguration, Collections.emptyMap());
        variants = buildVariantsIdx();
        assertTrue(variants.containsKey("1:10014:A:G"));
        assertEquals("0/2", variants.get("1:10014:A:G").getStudy(studyName).getSampleData("s1", "GT"));
        assertEquals("0/1", variants.get("1:10014:A:G").getStudy(studyName).getSampleData("s2", "GT"));

        assertTrue(variants.containsKey("1:10013:T:C"));
        assertEquals("0/1", variants.get("1:10013:T:C").getStudy(studyName).getSampleData("s1", "GT"));
        assertEquals("0/0", variants.get("1:10013:T:C").getStudy(studyName).getSampleData("s2", "GT"));

        // delete
        removeFile("s2.genome.vcf", studyConfiguration, Collections.emptyMap());
        variants = buildVariantsIdx();
        if (variants.containsKey("1:10014:A:G")) {
            System.out.println(variants.get("1:10014:A:G").getImpl());
        }
        assertFalse(variants.containsKey("1:10014:A:G"));

        assertTrue(variants.containsKey("1:10013:T:C"));
        assertEquals("0/1", variants.get("1:10013:T:C").getStudy(studyName).getSampleData("s1", "GT"));
        assertEquals(null, variants.get("1:10013:T:C").getStudy(studyName).getSampleData("s2", "GT"));
    }

    private Map<String, Variant> buildVariantsIdx() throws StorageManagerException, Exception {
        VariantHadoopDBAdaptor dbAdaptor = getVariantStorageManager().getDBAdaptor(DB_NAME);
        Map<String, Variant> variants = new HashMap<>();
        for (Variant variant : dbAdaptor) {
            String v = variant.toString();
            assertFalse(variants.containsKey(v));
            variants.put(v, variant);
            VariantAnnotation a = variant.getAnnotation();
            variant.setAnnotation(null);
            System.out.println(variant.toJson());
            variant.setAnnotation(a);
        }
        return variants;
    }

}
