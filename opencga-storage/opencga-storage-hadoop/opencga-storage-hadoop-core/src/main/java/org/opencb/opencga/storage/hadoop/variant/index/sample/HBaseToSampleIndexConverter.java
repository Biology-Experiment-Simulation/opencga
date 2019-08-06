package org.opencb.opencga.storage.hadoop.variant.index.sample;

import org.apache.commons.lang3.tuple.Pair;
import org.apache.hadoop.hbase.Cell;
import org.apache.hadoop.hbase.CellUtil;
import org.apache.hadoop.hbase.client.Result;
import org.apache.hadoop.hbase.util.Bytes;
import org.opencb.biodata.models.variant.Variant;
import org.opencb.biodata.tools.Converter;
import org.opencb.opencga.storage.hadoop.variant.converters.AbstractPhoenixConverter;
import org.opencb.opencga.storage.hadoop.variant.index.IndexUtils;
import org.opencb.opencga.storage.hadoop.variant.index.family.MendelianErrorSampleIndexConverter;
import org.opencb.opencga.storage.hadoop.variant.index.family.MendelianErrorSampleIndexConverter.MendelianErrorSampleIndexVariantIterator;
import org.opencb.opencga.storage.hadoop.variant.index.sample.SampleIndexEntry.SampleIndexGtEntry;
import org.opencb.opencga.storage.hadoop.variant.index.sample.SampleIndexVariantBiConverter.SampleIndexVariantIterator;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.opencb.opencga.storage.hadoop.variant.index.sample.SampleIndexSchema.*;

/**
 * Converts Results to SampleIndexEntry.
 * <p>
 * Created on 18/05/18.
 *
 * @author Jacobo Coll &lt;jacobo167@gmail.com&gt;
 */
public class HBaseToSampleIndexConverter implements Converter<Result, SampleIndexEntry> {

    private final SampleIndexVariantBiConverter converter;

    public HBaseToSampleIndexConverter() {
        converter = new SampleIndexVariantBiConverter();
    }

    public static Pair<String, String> parsePendingColumn(byte[] column) {
        if (Bytes.startsWith(column, PENDING_VARIANT_PREFIX_BYTES)) {
            int lastIndexOf = 0;
            for (int i = column.length - 1; i >= 0; i--) {
                if (column[i] == '_') {
                    lastIndexOf = i;
                    break;
                }
            }
            return Pair.of(Bytes.toString(column, PENDING_VARIANT_PREFIX.length(), lastIndexOf - PENDING_VARIANT_PREFIX.length()),
                    Bytes.toString(column, lastIndexOf + 1));
        } else {
            return null;
        }
    }

    @Override
    public SampleIndexEntry convert(Result result) {
        return convert(result, false);
    }

    public SampleIndexEntry convertCountersOnly(Result result) {
        return convert(result, true);
    }

    private SampleIndexEntry convert(Result result, boolean countOnly) {
        final Map<String, SampleIndexGtEntry> gts = new HashMap<>();
        MendelianErrorSampleIndexVariantIterator mendelianIterator = null;

        byte[] row = result.getRow();
        String chromosome = SampleIndexSchema.chromosomeFromRowKey(row);
        int batchStart = SampleIndexSchema.batchStartFromRowKey(row);

        for (Cell cell : result.rawCells()) {
            if (columnStartsWith(cell, META_PREFIX_BYTES)) {
                if (columnStartsWith(cell, GENOTYPE_COUNT_PREFIX_BYTES)) {
                    SampleIndexGtEntry gtEntry = gts.computeIfAbsent(getGt(cell, GENOTYPE_COUNT_PREFIX_BYTES), SampleIndexGtEntry::new);
                    gtEntry.setCount(Bytes.toInt(cell.getValueArray(), cell.getValueOffset(), cell.getValueLength()));
                    if (countOnly && !gtEntry.getVariants().hasNext()) {
                        // By default, gtEntry has an empty iterator. If so, use the counter.
                        gtEntry.setVariants(converter.toVariantsCountIterator(gtEntry.getCount()));
                    }
                } else if (columnStartsWith(cell, ANNOTATION_SUMMARY_PREFIX_BYTES)) {
                    gts.computeIfAbsent(getGt(cell, ANNOTATION_SUMMARY_PREFIX_BYTES), SampleIndexGtEntry::new)
                            .setAnnotationIndexGt(CellUtil.cloneValue(cell));
                } else if (columnStartsWith(cell, ANNOTATION_SUMMARY_COUNT_PREFIX_BYTES)) {
                    gts.computeIfAbsent(getGt(cell, ANNOTATION_SUMMARY_COUNT_PREFIX_BYTES), SampleIndexGtEntry::new)
                            .setAnnotationCounts(IndexUtils.countPerBitToObject(CellUtil.cloneValue(cell)));
                } else if (columnStartsWith(cell, FILE_PREFIX_BYTES)) {
                    gts.computeIfAbsent(getGt(cell, FILE_PREFIX_BYTES), SampleIndexGtEntry::new)
                            .setFileIndexGt(CellUtil.cloneValue(cell));
                } else if (columnStartsWith(cell, PARENTS_PREFIX_BYTES)) {
                    gts.computeIfAbsent(getGt(cell, PARENTS_PREFIX_BYTES), SampleIndexGtEntry::new)
                            .setParentsGt(CellUtil.cloneValue(cell));
                } else if (columnStartsWith(cell, ANNOTATION_CT_PREFIX_BYTES)) {
                    gts.computeIfAbsent(getGt(cell, ANNOTATION_CT_PREFIX_BYTES), SampleIndexGtEntry::new)
                            .setConsequenceTypeIndexGt(CellUtil.cloneValue(cell));
                } else if (columnStartsWith(cell, ANNOTATION_BT_PREFIX_BYTES)) {
                    gts.computeIfAbsent(getGt(cell, ANNOTATION_BT_PREFIX_BYTES), SampleIndexGtEntry::new)
                            .setBiotypeIndexGt(CellUtil.cloneValue(cell));
                } else if (columnStartsWith(cell, ANNOTATION_POP_FREQ_PREFIX_BYTES)) {
                    gts.computeIfAbsent(getGt(cell, ANNOTATION_POP_FREQ_PREFIX_BYTES), SampleIndexGtEntry::new)
                            .setPopulationFrequencyIndexGt(CellUtil.cloneValue(cell));
                }
            } else {
                if (columnStartsWith(cell, MENDELIAN_ERROR_COLUMN_BYTES)) {
                    mendelianIterator = MendelianErrorSampleIndexConverter.toVariants(
                            cell.getValueArray(), cell.getValueOffset(), cell.getValueLength());
                } else {
                    String gt = Bytes.toString(CellUtil.cloneQualifier(cell));
                    SampleIndexGtEntry gtEntry = gts.computeIfAbsent(gt, SampleIndexGtEntry::new);

                    SampleIndexVariantIterator variants = converter.toVariantsIterator(chromosome, batchStart,
                            cell.getValueArray(), cell.getValueOffset(), cell.getValueLength());
                    gtEntry.setVariants(variants);
                }
            }
        }

        if (mendelianIterator != null) {
            for (SampleIndexGtEntry gtEntry : gts.values()) {
                mendelianIterator.addAnnotationIndex(gtEntry.getGt(), gtEntry.getAnnotationIndexGt());
            }
        }

        return new SampleIndexEntry(chromosome, batchStart, gts, mendelianIterator);
    }

    public static boolean columnStartsWith(Cell cell, byte[] prefix) {
        return AbstractPhoenixConverter.columnStartsWith(cell, prefix);
    }

    public Map<String, List<Variant>> convertToMap(Result result) {
        Map<String, List<Variant>> map = new HashMap<>();
        for (Cell cell : result.rawCells()) {
            String gt = Bytes.toString(CellUtil.cloneQualifier(cell));
            if (gt.charAt(0) != META_PREFIX) {
                map.put(gt, converter.toVariants(cell));
            }
        }
        return map;
    }

    public int convertToCount(Result result) {
        int count = 0;
        for (Cell cell : result.rawCells()) {
            String column = Bytes.toString(CellUtil.cloneQualifier(cell));
            if (column.startsWith(GENOTYPE_COUNT_PREFIX)) {
                count += Bytes.toInt(cell.getValueArray(), cell.getValueOffset(), cell.getValueLength());
            }
        }
        return count;
    }
}
