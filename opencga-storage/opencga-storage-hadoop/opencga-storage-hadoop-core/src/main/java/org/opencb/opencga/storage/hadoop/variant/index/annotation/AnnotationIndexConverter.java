package org.opencb.opencga.storage.hadoop.variant.index.annotation;

import org.apache.commons.collections.CollectionUtils;
import org.apache.commons.lang.StringUtils;
import org.apache.commons.lang3.tuple.Pair;
import org.apache.hadoop.hbase.Cell;
import org.apache.hadoop.hbase.CellUtil;
import org.apache.hadoop.hbase.client.Put;
import org.apache.hadoop.hbase.client.Result;
import org.apache.hadoop.hbase.util.Bytes;
import org.opencb.biodata.models.variant.Variant;
import org.opencb.biodata.models.variant.VariantBuilder;
import org.opencb.biodata.models.variant.avro.*;
import org.opencb.cellbase.core.variant.annotation.VariantAnnotationUtils;
import org.opencb.opencga.storage.hadoop.variant.index.phoenix.VariantPhoenixKeyFactory;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.opencb.biodata.models.variant.StudyEntry.DEFAULT_COHORT;
import static org.opencb.opencga.storage.hadoop.variant.index.phoenix.VariantPhoenixKeyFactory.generateVariantRowKey;

/**
 * Created by jacobo on 04/01/19.
 */
public class AnnotationIndexConverter {

    public static final byte EMPTY_ANNOTATION_MASK = 0;

    public static final String PROTEIN_CODING = VariantAnnotationUtils.PROTEIN_CODING;
    public static final String MISSENSE_VARIANT = VariantAnnotationUtils.MISSENSE_VARIANT;
    public static final String GNOMAD_GENOMES = "GNOMAD_GENOMES";
    public static final double POP_FREQ_THRESHOLD_001 = 0.001;
//    public static final double POP_FREQ_THRESHOLD_005 = 0.005;
    public static final Set<String> LOF_SET = new HashSet<>();
    public static final String K_GENOMES = "1kG_phase3";

    public static final byte PROTEIN_CODING_MASK      = (byte) (1 << 0);
    public static final byte POP_FREQ_001_MASK        = (byte) (1 << 1);
    public static final byte UNUSED_2_MASK            = (byte) (1 << 2);
    public static final byte MISSENSE_VARIANT_MASK    = (byte) (1 << 3);
    public static final byte LOF_MASK                 = (byte) (1 << 4);
    public static final byte CLINICAL_MASK            = (byte) (1 << 5);
    public static final byte NON_SNV_MASK             = (byte) (1 << 6);
    public static final byte UNUSED_7_MASK            = (byte) (1 << 7);

    protected static final byte[] COLUMN_FMAILY = Bytes.toBytes("0");
    protected static final byte[] VALUE_COLUMN = Bytes.toBytes("v");

    static {
        LOF_SET.add(VariantAnnotationUtils.FRAMESHIFT_VARIANT);
        LOF_SET.add(VariantAnnotationUtils.INFRAME_DELETION);
        LOF_SET.add(VariantAnnotationUtils.INFRAME_INSERTION);
        LOF_SET.add(VariantAnnotationUtils.START_LOST);
        LOF_SET.add(VariantAnnotationUtils.STOP_GAINED);
        LOF_SET.add(VariantAnnotationUtils.STOP_LOST);
        LOF_SET.add(VariantAnnotationUtils.SPLICE_ACCEPTOR_VARIANT);
        LOF_SET.add(VariantAnnotationUtils.SPLICE_DONOR_VARIANT);
        LOF_SET.add(VariantAnnotationUtils.TRANSCRIPT_ABLATION);
        LOF_SET.add(VariantAnnotationUtils.TRANSCRIPT_AMPLIFICATION);
    }

    public static Pair<Variant, Byte> getVariantBytePair(Result result) {
        Variant variant = VariantPhoenixKeyFactory.extractVariantFromVariantRowKey(result.getRow());
        Cell cell = result.getColumnLatestCell(COLUMN_FMAILY, VALUE_COLUMN);
        byte[] value = CellUtil.cloneValue(cell);
        return Pair.of(variant, value[0]);
    }

    public static String maskToString(byte b) {
        String str = Integer.toBinaryString(b);
        if (str.length() > 8) {
            str = str.substring(str.length() - 8);
        }
        return StringUtils.leftPad(str, 8, '0');
    }

    public byte[] convert(List<VariantAnnotation> list) {
        byte[] bytes = new byte[list.size()];

        int i = 0;
        for (VariantAnnotation variantAnnotation : list) {
            bytes[i] = convert(variantAnnotation);
            i++;
        }

        return bytes;
    }

    public byte convert(VariantAnnotation variantAnnotation) {
        byte b = 0;

        VariantType type = VariantBuilder.inferType(variantAnnotation.getReference(), variantAnnotation.getAlternate());
        if (!type.equals(VariantType.SNV) && !type.equals(VariantType.SNP)) {
            b |= NON_SNV_MASK;
        }

        if (variantAnnotation.getConsequenceTypes() != null) {
            for (ConsequenceType ct : variantAnnotation.getConsequenceTypes()) {
                if (PROTEIN_CODING.equals(ct.getBiotype())) {
                    b |= PROTEIN_CODING_MASK;
                }
                for (SequenceOntologyTerm sequenceOntologyTerm : ct.getSequenceOntologyTerms()) {
                    String soName = sequenceOntologyTerm.getName();
                    if (MISSENSE_VARIANT.equals(soName)) {
                        b |= MISSENSE_VARIANT_MASK;
                    } else if (LOF_SET.contains(soName)) {
                        b |= LOF_MASK;
                    }
                }
            }
        }

        // By default, population frequency is 0.
        double minFreq = 0;
        if (variantAnnotation.getPopulationFrequencies() != null) {
            double gnomadFreq = 0;
            double kgenomesFreq = 0;
            for (PopulationFrequency populationFrequency : variantAnnotation.getPopulationFrequencies()) {
                if (populationFrequency.getPopulation().equals(DEFAULT_COHORT)) {
                    if (populationFrequency.getStudy().equals(GNOMAD_GENOMES)) {
                        gnomadFreq = populationFrequency.getAltAlleleFreq();
                    } else if (populationFrequency.getStudy().equals(K_GENOMES)) {
                        kgenomesFreq = populationFrequency.getAltAlleleFreq();
                    }
                }
            }
            minFreq = Math.min(gnomadFreq, kgenomesFreq);
        }
//        if (minFreq < POP_FREQ_THRESHOLD_005) {
//            b |= POP_FREQ_005_MASK;
//        }
        if (minFreq < POP_FREQ_THRESHOLD_001) {
            b |= POP_FREQ_001_MASK;
        }

        if (CollectionUtils.isNotEmpty(variantAnnotation.getTraitAssociation())) {
            b |= CLINICAL_MASK;
        }
        return b;
    }

    public List<Put> convertToPut(List<VariantAnnotation> variantAnnotations) {
        List<Put> puts = new ArrayList<>(variantAnnotations.size());
        for (VariantAnnotation variantAnnotation : variantAnnotations) {
            puts.add(convertToPut(variantAnnotation));
        }
        return puts;
    }

    public Put convertToPut(VariantAnnotation variantAnnotation) {
        byte[] bytesRowKey = generateVariantRowKey(variantAnnotation);
        Put put = new Put(bytesRowKey);
        byte value = convert(variantAnnotation);
        put.addColumn(COLUMN_FMAILY, VALUE_COLUMN, new byte[]{value});
        return put;
    }
}