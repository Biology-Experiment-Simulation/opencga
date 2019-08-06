package org.opencb.opencga.storage.hadoop.variant.index.sample;

import htsjdk.variant.vcf.VCFConstants;
import org.apache.commons.lang.StringUtils;
import org.apache.commons.lang3.tuple.Pair;
import org.opencb.biodata.models.core.Region;
import org.opencb.biodata.models.variant.annotation.ConsequenceTypeMappings;
import org.opencb.biodata.models.variant.avro.VariantType;
import org.opencb.cellbase.core.variant.annotation.VariantAnnotationUtils;
import org.opencb.commons.datastore.core.Query;
import org.opencb.opencga.storage.core.metadata.VariantStorageMetadataManager;
import org.opencb.opencga.storage.core.metadata.models.SampleMetadata;
import org.opencb.opencga.storage.core.metadata.models.StudyMetadata;
import org.opencb.opencga.storage.core.metadata.models.TaskMetadata;
import org.opencb.opencga.storage.core.variant.VariantStorageEngine;
import org.opencb.opencga.storage.core.variant.adaptors.GenotypeClass;
import org.opencb.opencga.storage.core.variant.adaptors.VariantQueryException;
import org.opencb.opencga.storage.core.variant.adaptors.VariantQueryParam;
import org.opencb.opencga.storage.core.variant.adaptors.VariantQueryUtils;
import org.opencb.opencga.storage.core.variant.query.VariantQueryParser;
import org.opencb.opencga.storage.hadoop.variant.index.IndexUtils;
import org.opencb.opencga.storage.hadoop.variant.index.family.GenotypeCodec;
import org.opencb.opencga.storage.hadoop.variant.index.query.RangeQuery;
import org.opencb.opencga.storage.hadoop.variant.index.query.SampleFileIndexQuery;
import org.opencb.opencga.storage.hadoop.variant.index.query.SampleIndexQuery;
import org.opencb.opencga.storage.hadoop.variant.index.sample.SampleIndexConfiguration.PopulationFrequencyRange;
import org.opencb.opencga.storage.hadoop.variant.index.query.SampleAnnotationIndexQuery;
import org.opencb.opencga.storage.hadoop.variant.index.query.SampleAnnotationIndexQuery.PopulationFrequencyQuery;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.function.Function;
import java.util.stream.Collectors;

import static org.opencb.opencga.storage.core.variant.adaptors.VariantQueryParam.*;
import static org.opencb.opencga.storage.core.variant.adaptors.VariantQueryUtils.*;
import static org.opencb.opencga.storage.hadoop.variant.adaptors.phoenix.VariantSqlQueryParser.DEFAULT_LOADED_GENOTYPES;
import static org.opencb.opencga.storage.hadoop.variant.index.annotation.AnnotationIndexConverter.*;
import static org.opencb.opencga.storage.hadoop.variant.index.sample.VariantFileIndexConverter.TYPE_OTHER_CODE;

/**
 * Created by jacobo on 06/01/19.
 */
public class SampleIndexQueryParser {
    private static Logger logger = LoggerFactory.getLogger(SampleIndexQueryParser.class);
    private final SampleIndexConfiguration configuration;
    private final VariantStorageMetadataManager metadataManager;

    public SampleIndexQueryParser(VariantStorageMetadataManager metadataManager) {
        this(metadataManager, SampleIndexConfiguration.defaultConfiguration());
    }

    public SampleIndexQueryParser(VariantStorageMetadataManager metadataManager, SampleIndexConfiguration configuration) {
        this.configuration = configuration;
        this.metadataManager = metadataManager;
    }

    /**
     * Determine if a given query can be used to query with the SampleIndex.
     * @param query Query
     * @return      if the query is valid
     */
    public static boolean validSampleIndexQuery(Query query) {
        VariantQueryParser.VariantQueryXref xref = VariantQueryParser.parseXrefs(query);
        if (!xref.getIds().isEmpty() || !xref.getVariants().isEmpty() || !xref.getOtherXrefs().isEmpty()) {
            // Can not be used for specific variant IDs. Only regions and genes
            return false;
        }

        if (isValidParam(query, GENOTYPE)) {
            HashMap<Object, List<String>> gtMap = new HashMap<>();
            QueryOperation queryOperation = VariantQueryUtils.parseGenotypeFilter(query.getString(GENOTYPE.key()), gtMap);
            boolean allValid = true;
            boolean anyValid = false;
            for (List<String> gts : gtMap.values()) {
                boolean valid = true;
                for (String gt : gts) {
                    // Despite invalid genotypes (i.e. genotypes not in the index) can be used to filter within AND queries,
                    // we require at least one sample where all the genotypes are valid
                    valid &= SampleIndexSchema.validGenotype(gt);
                    valid &= !isNegated(gt);
                }
                anyValid |= valid;
                allValid &= valid;
            }
            if (queryOperation == QueryOperation.AND) {
                // Intersect sample filters. If any sample filter is valid, the SampleIndex can be used.
                return anyValid;
            } else {
                // Union of all sample filters. All sample filters must be valid to use the SampleIndex.
                return allValid;
            }
        }
        if (isValidParam(query, SAMPLE, true)) {
            return true;
        }
        if (isValidParam(query, SAMPLE_MENDELIAN_ERROR, true)) {
            return true;
        }
        if (isValidParam(query, SAMPLE_DE_NOVO, true)) {
            return true;
        }
        return false;
    }


    /**
     * Build SampleIndexQuery. Extract Regions (+genes), Study, Sample and Genotypes.
     * <p>
     * Assumes that the query is valid.
     *
     * @param query           Input query. Will be modified.
     * @return Valid SampleIndexQuery
     * @see SampleIndexQueryParser#validSampleIndexQuery(Query)
     */
    public SampleIndexQuery parse(Query query) {
        //
        // Extract regions
        List<Region> regions = new ArrayList<>();
        if (isValidParam(query, REGION)) {
            regions.addAll(Region.parseRegions(query.getString(REGION.key())));
            query.remove(REGION.key());
        }

        if (isValidParam(query, ANNOT_GENE_REGIONS)) {
            regions.addAll(Region.parseRegions(query.getString(ANNOT_GENE_REGIONS.key())));
            query.remove(ANNOT_GENE_REGIONS.key());
            query.remove(GENE.key());
        }

        regions = mergeRegions(regions);

        // TODO: Accept variant IDs?

        // Extract study
        StudyMetadata defaultStudy = VariantQueryUtils.getDefaultStudy(query, null, metadataManager);

        if (defaultStudy == null) {
            throw VariantQueryException.missingStudyForSample("", metadataManager.getStudyNames());
        }
        int studyId = defaultStudy.getId();

        List<String> allGenotypes = getAllLoadedGenotypes(defaultStudy);
        List<String> validGenotypes = allGenotypes.stream().filter(SampleIndexSchema::validGenotype).collect(Collectors.toList());
        List<String> mainGenotypes = GenotypeClass.MAIN_ALT.filter(validGenotypes);

        Set<String> mendelianErrorSet = Collections.emptySet();
        boolean onlyDeNovo = false;
        Map<String, boolean[]> fatherFilterMap = new HashMap<>();
        Map<String, boolean[]> motherFilterMap = new HashMap<>();

        boolean partialIndex = false;

        // Extract sample and genotypes to filter
        QueryOperation queryOperation;
        Map<String, List<String>> samplesMap = new HashMap<>();
        List<String> otherSamples = new LinkedList<>();
        if (isValidParam(query, GENOTYPE)) {
            // Get samples with non negated genotypes

            Map<Object, List<String>> map = new HashMap<>();
            queryOperation = parseGenotypeFilter(query.getString(GENOTYPE.key()), map);

            // Extract parents from each sample
            Map<String, List<String>> gtMap = new HashMap<>();
            Map<String, List<String>> parentsMap = new HashMap<>();
            for (Map.Entry<Object, List<String>> entry : map.entrySet()) {
                Object sample = entry.getKey();
                Integer sampleId = metadataManager.getSampleId(studyId, sample);
                SampleMetadata sampleMetadata = metadataManager.getSampleMetadata(studyId, sampleId);

                if (sampleMetadata.getFamilyIndexStatus() == TaskMetadata.Status.READY) {
                    String fatherName = null;
                    if (sampleMetadata.getFather() != null) {
                        fatherName = metadataManager.getSampleName(studyId, sampleMetadata.getFather());
                    }
                    String motherName = null;
                    if (sampleMetadata.getMother() != null) {
                        motherName = metadataManager.getSampleName(studyId, sampleMetadata.getMother());
                    }
                    if (fatherName != null || motherName != null) {
                        parentsMap.put(sampleMetadata.getName(), Arrays.asList(fatherName, motherName));
                    }
                }

                gtMap.put(sampleMetadata.getName(), entry.getValue());
            }

            // Determine which samples are parents, and which are children
            Set<String> childrenSet = findChildren(gtMap, queryOperation, parentsMap);
            Set<String> parentsSet = new HashSet<>();
            for (String child : childrenSet) {
                // may add null values
                parentsSet.addAll(parentsMap.get(child));
            }

            boolean covered = true;
            if (isValidParam(query, FORMAT)) {
                covered = false;
            }
            for (Map.Entry<String, List<String>> entry : gtMap.entrySet()) {
                String sampleName = entry.getKey();
                if (queryOperation != QueryOperation.OR && parentsSet.contains(sampleName) && !childrenSet.contains(sampleName)) {
                    // We can skip parents, as their genotype filter will be tested in the child
                    // Discard parents that are not children of another sample
                    // Parents filter can only be used when intersecting (AND) with child
                    logger.debug("Discard parent {}", sampleName);
                    continue;
                }
                if (hasNegatedGenotypeFilter(queryOperation, entry.getValue())) {
                    samplesMap.put(sampleName, entry.getValue());
                    if (queryOperation != QueryOperation.OR && childrenSet.contains(sampleName)) {
                        // Parents filter can only be used when intersecting (AND) with child
                        List<String> parents = parentsMap.get(sampleName);
                        String father = parents.get(0);
                        String mother = parents.get(1);

                        if (father != null) {
                            boolean[] filter = buildParentGtFilter(gtMap.get(father));
                            if (!isFullyCoveredParentFilter(filter)) {
                                covered = false;
                            }
                            fatherFilterMap.put(sampleName, filter);
                        }
                        if (mother != null) {
                            boolean[] filter = buildParentGtFilter(gtMap.get(mother));
                            if (!isFullyCoveredParentFilter(filter)) {
                                covered = false;
                            }
                            motherFilterMap.put(sampleName, filter);
                        }
                    }
                } else {
                    otherSamples.add(sampleName);
                    covered = false;
                }
                // If not all genotypes are valid, query is not covered
                if (!entry.getValue().stream().allMatch(SampleIndexSchema::validGenotype)) {
                    covered = false;
                }
            }

            if (covered) {
                query.remove(GENOTYPE.key());
            }
        } else if (isValidParam(query, SAMPLE)) {
            // Filter by all non negated samples
            String samplesStr = query.getString(SAMPLE.key());
            queryOperation = VariantQueryUtils.checkOperator(samplesStr);
            List<String> samples = VariantQueryUtils.splitValue(samplesStr, queryOperation);
            for (String s : samples) {
                if (!isNegated(s)) {
                    samplesMap.put(s, mainGenotypes);
                }
            }

            if (!isValidParam(query, FORMAT)) {
                // Do not remove FORMAT
                query.remove(SAMPLE.key());
            }
            //} else if (isValidParam(query, FILE)) {
            // TODO: Add FILEs filter
        } else if (isValidParam(query, SAMPLE_MENDELIAN_ERROR)) {
            onlyDeNovo = false;
            Pair<QueryOperation, List<String>> mendelianError = splitValue(query.getString(SAMPLE_MENDELIAN_ERROR.key()));
            mendelianErrorSet = new HashSet<>(mendelianError.getValue());
            queryOperation = mendelianError.getKey();
            for (String s : mendelianErrorSet) {
                // Return any genotype
                samplesMap.put(s, mainGenotypes);
            }
            query.remove(SAMPLE_MENDELIAN_ERROR.key());
            // Reading any MendelianError could return variants from GT=0/0, which is not annotated in the SampleIndex,
            // so the index is partial.
            partialIndex = true;
        } else if (isValidParam(query, SAMPLE_DE_NOVO)) {
            onlyDeNovo = true;
            Pair<QueryOperation, List<String>> mendelianError = splitValue(query.getString(SAMPLE_DE_NOVO.key()));
            mendelianErrorSet = new HashSet<>(mendelianError.getValue());
            queryOperation = mendelianError.getKey();
            for (String s : mendelianErrorSet) {
                // Return any genotype
                samplesMap.put(s, mainGenotypes);
            }
            query.remove(SAMPLE_DE_NOVO.key());
        } else {
            throw new IllegalStateException("Unable to query SamplesIndex");
        }

        String study = defaultStudy.getName();

        Map<String, SampleFileIndexQuery> fileIndexMap = new HashMap<>(samplesMap.size());
        for (String sample : samplesMap.keySet()) {
            SampleFileIndexQuery fileIndexQuery = parseFileQuery(query, sample, s -> {
                Integer sampleId = metadataManager.getSampleId(studyId, s);
                Set<Integer> fileIds = metadataManager.getFileIdsFromSampleIds(studyId, Collections.singleton(sampleId));
                List<String> fileNames = new ArrayList<>(fileIds.size());
                for (Integer fileId : fileIds) {
                    fileNames.add(metadataManager.getFileName(studyId, fileId));
                }
                return fileNames;
            });

            fileIndexMap.put(sample, fileIndexQuery);
        }
        boolean allSamplesAnnotated = true;
        if (otherSamples.isEmpty()) {
            for (String sample : samplesMap.keySet()) {
                Integer sampleId = metadataManager.getSampleId(studyId, sample);
                SampleMetadata sampleMetadata = metadataManager.getSampleMetadata(studyId, sampleId);
                if (!sampleMetadata.getStatus(SampleIndexAnnotationLoader.SAMPLE_INDEX_STATUS).equals(TaskMetadata.Status.READY)) {
                    allSamplesAnnotated = false;
                    break;
                }
            }
        } else {
            allSamplesAnnotated = false;
        }

        boolean completeIndex = allSamplesAnnotated && !partialIndex;
        SampleAnnotationIndexQuery annotationIndexQuery = parseAnnotationIndexQuery(query, completeIndex);
        Set<VariantType> variantTypes = null;
        if (isValidParam(query, TYPE)) {
            List<String> typesStr = query.getAsStringList(VariantQueryParam.TYPE.key());
            if (!typesStr.isEmpty()) {
                variantTypes = new HashSet<>(typesStr.size());
                for (String type : typesStr) {
                    variantTypes.add(VariantType.valueOf(type));
                }
                if (variantTypes.contains(VariantType.SNP)) {
                    // Can not distinguish between SNP and SNV. Filter only by SNV
                    variantTypes.remove(VariantType.SNP);
                    variantTypes.add(VariantType.SNV);
                }
                if (variantTypes.contains(VariantType.MNP)) {
                    // Can not distinguish between MNP and MNV. Filter only by MNV
                    variantTypes.remove(VariantType.MNP);
                    variantTypes.add(VariantType.MNV);
                }
            }
            if (!hasSNPFilter(typesStr) && !hasMNPFilter(typesStr)) {
                query.remove(TYPE.key());
            }
        }

        return new SampleIndexQuery(regions, variantTypes, study, samplesMap, fatherFilterMap, motherFilterMap, fileIndexMap,
                annotationIndexQuery,
                mendelianErrorSet, onlyDeNovo, queryOperation);
    }

    protected static boolean hasNegatedGenotypeFilter(QueryOperation queryOperation, List<String> gts) {
        boolean valid = true;
        for (String gt : gts) {
            if (queryOperation == QueryOperation.OR && !SampleIndexSchema.validGenotype(gt)) {
                // Invalid genotypes (i.e. genotypes not in the index) are not allowed in OR queries
                throw new IllegalStateException("Genotype '" + gt + "' not in the SampleIndex.");
            }
            valid &= !isNegated(gt);
        }
        return valid;
    }

    /**
     * Determine which samples are valid children.
     *
     * i.e. sample with non negated genotype filter and parents in the query
     *
     * @param gtMap Genotype filter map
     * @param queryOperation Query operation
     * @param parentsMap Parents map
     * @return Set with all children from the query
     */
    protected Set<String> findChildren(Map<String, List<String>> gtMap, QueryOperation queryOperation,
                                              Map<String, List<String>> parentsMap) {
        Set<String> childrenSet = new HashSet<>(parentsMap.size());
        for (Map.Entry<String, List<String>> entry : parentsMap.entrySet()) {
            String child = entry.getKey();
            List<String> parents = entry.getValue();

            if (!hasNegatedGenotypeFilter(queryOperation, gtMap.get(child))) {
                // Discard children with negated iterators
                continue;
            }

            // Remove parents not in query
            for (int i = 0; i < parents.size(); i++) {
                String parent = parents.get(i);
                if (!gtMap.containsKey(parent)) {
                    parents.set(i, null);
                }
            }

            String father = parents.get(0);
            String mother = parents.get(1);
            if (father != null || mother != null) {
                // Is a child if has any parent
                childrenSet.add(child);
            }
        }
        return childrenSet;
    }

    protected static boolean[] buildParentGtFilter(List<String> parentGts) {
        boolean[] filter = new boolean[GenotypeCodec.NUM_CODES]; // all false by default
        for (String gt : parentGts) {
            filter[GenotypeCodec.encode(gt)] = true;
        }
        return filter;
    }

    public static boolean isFullyCoveredParentFilter(boolean[] filter) {
        for (int i = 0; i < filter.length; i++) {
            if (filter[i]) {
                if (GenotypeCodec.isAmbiguousCode(i)) {
                    return false;
                }
            }
        }
        return true;
    }

    protected SampleFileIndexQuery parseFileQuery(Query query, String sample, Function<String, Collection<String>> filesFromSample) {
        byte fileIndexMask = 0;

        Set<Integer> typeCodes = Collections.emptySet();

        if (isValidParam(query, TYPE)) {
            List<String> types = new ArrayList<>(query.getAsStringList(VariantQueryParam.TYPE.key()));
            if (!types.isEmpty()) {
                typeCodes = new HashSet<>(types.size());
                fileIndexMask |= VariantFileIndexConverter.TYPE_1_MASK;
                fileIndexMask |= VariantFileIndexConverter.TYPE_2_MASK;
                fileIndexMask |= VariantFileIndexConverter.TYPE_3_MASK;

                for (String type : types) {
                    typeCodes.add(VariantFileIndexConverter.getTypeCode(VariantType.valueOf(type.toUpperCase())));
                }
            }
            if (!typeCodes.contains(TYPE_OTHER_CODE)
                    && !hasSNPFilter(types)
                    && !hasMNPFilter(types)) {
                query.remove(TYPE.key());
            }
        }

        boolean filterPass = false;
        if (isValidParam(query, FILTER)) {
            List<String> filterValues = splitValue(query.getString(FILTER.key())).getRight();

            if (filterValues.size() == 1) {
                if (filterValues.get(0).equals(VCFConstants.PASSES_FILTERS_v4)) {
                    // PASS
                    fileIndexMask |= VariantFileIndexConverter.FILTER_PASS_MASK;
                    filterPass = true;
                } else if (filterValues.get(0).equals(VariantQueryUtils.NOT + VCFConstants.PASSES_FILTERS_v4)) {
                    // !PASS
                    fileIndexMask |= VariantFileIndexConverter.FILTER_PASS_MASK;
                } else if (!isNegated(filterValues.get(0))) {
                    // Non negated filter, other than PASS
                    fileIndexMask |= VariantFileIndexConverter.FILTER_PASS_MASK;
                }
            } else {
                if (!filterValues.contains(VCFConstants.PASSES_FILTERS_v4)) {
                    if (filterValues.stream().noneMatch(VariantQueryUtils::isNegated)) {
                        // None negated filter, without PASS
                        fileIndexMask |= VariantFileIndexConverter.FILTER_PASS_MASK;
                    }
                } // else --> Mix PASS and other filters. Can not use index
            }
        }

        RangeQuery qualQuery = null;
        if (isValidParam(query, QUAL)) {
            String qualValue = query.getString(QUAL.key());
            List<String> qualValues = VariantQueryUtils.splitValue(qualValue).getValue();
            if (qualValues.size() == 1) {

                fileIndexMask |= VariantFileIndexConverter.QUAL_1_MASK;
                fileIndexMask |= VariantFileIndexConverter.QUAL_2_MASK;

                String[] split = VariantQueryUtils.splitOperator(qualValue);
                String op = split[1];
                double value = Double.valueOf(split[2]);
                qualQuery = getRangeQuery(op, value, SampleIndexConfiguration.QUAL_THRESHOLDS, 0, IndexUtils.MAX);
                fileIndexMask |= VariantFileIndexConverter.QUAL_MASK;
            }
        }

        RangeQuery dpQuery = null;
        if (isValidParam(query, INFO)) {
            Map<String, String> infoMap = VariantQueryUtils.parseInfo(query).getValue();
            // Lazy get files from sample
            Collection<String> files = filesFromSample.apply(sample);
            for (String file : files) {
                String values = infoMap.get(file);

                if (StringUtils.isNotEmpty(values)) {
                    for (String value : VariantQueryUtils.splitValue(values).getValue()) {
                        String[] split = VariantQueryUtils.splitOperator(value);
                        if (split[0].equals(VCFConstants.DEPTH_KEY)) {
                            String op = split[1];
                            double dpValue = Double.parseDouble(split[2]);
                            dpQuery = getRangeQuery(op, dpValue, SampleIndexConfiguration.DP_THRESHOLDS, 0, IndexUtils.MAX);
                            fileIndexMask |= VariantFileIndexConverter.DP_MASK;
                        }
                    }
                }
            }
        }

        if (isValidParam(query, FORMAT)) {
            Map<String, String> format = VariantQueryUtils.parseFormat(query).getValue();
            String values = format.get(sample);

            if (StringUtils.isNotEmpty(values)) {
                for (String value : VariantQueryUtils.splitValue(values).getValue()) {
                    String[] split = VariantQueryUtils.splitOperator(value);
                    if (split[0].equals(VCFConstants.DEPTH_KEY)) {
                        String op = split[1];
                        double dpValue = Double.parseDouble(split[2]);
                        dpQuery = getRangeQuery(op, dpValue, SampleIndexConfiguration.DP_THRESHOLDS, 0, IndexUtils.MAX);
                        fileIndexMask |= VariantFileIndexConverter.DP_MASK;
                    }
                }
            }
        }

        boolean[] validFileIndex = new boolean[1 << Byte.SIZE];

        if (fileIndexMask != IndexUtils.EMPTY_MASK) {
            int qualMin = qualQuery == null ? 0 : qualQuery.getMinCodeInclusive();
            int qualMax = qualQuery == null ? 1 : qualQuery.getMaxCodeExclusive();
            int dpMin = dpQuery == null ? 0 : dpQuery.getMinCodeInclusive();
            int dpMax = dpQuery == null ? 1 : dpQuery.getMaxCodeExclusive();
            if (typeCodes.isEmpty()) {
                typeCodes = Collections.singleton(0);
            }
            for (Integer typeCode : typeCodes) {
                for (int q = qualMin; q < qualMax; q++) {
                    for (int dp = dpMin; dp < dpMax; dp++) {

                        int validFile = 0;
                        if (filterPass) {
                            validFile = VariantFileIndexConverter.FILTER_PASS_MASK;
                        }

                        validFile |= typeCode << VariantFileIndexConverter.TYPE_SHIFT;
                        validFile |= q << VariantFileIndexConverter.QUAL_SHIFT;
                        validFile |= dp << VariantFileIndexConverter.DP_SHIFT;

                        validFileIndex[validFile] = true;
                    }
                }
            }
        }

        return new SampleFileIndexQuery(sample, fileIndexMask, qualQuery, dpQuery, validFileIndex);
    }

    private boolean hasSNPFilter(List<String> types) {
        return types.contains(VariantType.SNP.name()) && !types.contains(VariantType.SNV.name());
    }

    private boolean hasMNPFilter(List<String> types) {
        return types.contains(VariantType.MNP.name()) && !types.contains(VariantType.MNV.name());
    }

    protected SampleAnnotationIndexQuery parseAnnotationIndexQuery(Query query) {
        return parseAnnotationIndexQuery(query, false);
    }

    /**
     * Builds the SampleAnnotationIndexQuery given a VariantQuery.
     *
     * @param query Input VariantQuery. If the index is complete, covered filters could be removed from here.
     * @param completeIndex Indicates if the index is complete for the samples in the query.
     * @return SampleAnnotationIndexQuery
     */
    protected SampleAnnotationIndexQuery parseAnnotationIndexQuery(Query query, boolean completeIndex) {
        byte annotationIndex = 0;
        byte biotypeMask = 0;
        short consequenceTypeMask = 0;

        Boolean intergenic = null;

        if (!isValidParam(query, REGION)) {
            VariantQueryParser.VariantQueryXref variantQueryXref = VariantQueryParser.parseXrefs(query);
            if (!variantQueryXref.getGenes().isEmpty()
                    && variantQueryXref.getIds().isEmpty()
                    && variantQueryXref.getOtherXrefs().isEmpty()
                    && variantQueryXref.getVariants().isEmpty()) {
                // If only filtering by genes, is not intergenic.
                intergenic = false;
            }
        }

        BiotypeConsquenceTypeFlagCombination combination = BiotypeConsquenceTypeFlagCombination.fromQuery(query);

        if (isValidParam(query, ANNOT_CONSEQUENCE_TYPE)) {
            List<String> soNames = query.getAsStringList(VariantQueryParam.ANNOT_CONSEQUENCE_TYPE.key());
            soNames = soNames.stream()
                    .map(ct -> ConsequenceTypeMappings.accessionToTerm.get(VariantQueryUtils.parseConsequenceType(ct)))
                    .collect(Collectors.toList());
            if (!soNames.contains(VariantAnnotationUtils.INTERGENIC_VARIANT)
                    && !soNames.contains(VariantAnnotationUtils.REGULATORY_REGION_VARIANT)
                    && !soNames.contains(VariantAnnotationUtils.TF_BINDING_SITE_VARIANT)) {
                // All ct values but "intergenic_variant" and "regulatory_region_variant" are in genes (i.e. non-intergenic)
                intergenic = false;
            } else if (soNames.size() == 1 && soNames.contains(VariantAnnotationUtils.INTERGENIC_VARIANT)) {
                intergenic = true;
            }
            boolean ctFilterCoveredBySummary = false;
            if (LOF_SET.containsAll(soNames)) {
                ctFilterCoveredBySummary = soNames.size() == LOF_SET.size();
                annotationIndex |= LOF_MASK;
                // If all present, remove consequenceType filter
                if (completeIndex && LOF_SET.size() == soNames.size()) {
                    // Ensure not filtering by gene, and not combining with other params
                    if (!isValidParam(query, GENE) && simpleCombination(combination)) {
                        query.remove(ANNOT_CONSEQUENCE_TYPE.key());
                    }
                }
            }
            if (LOF_EXTENDED_SET.containsAll(soNames)) {
                boolean proteinCodingOnly = query.getString(ANNOT_BIOTYPE.key()).equals(VariantAnnotationUtils.PROTEIN_CODING);
                ctFilterCoveredBySummary = soNames.size() == LOF_EXTENDED_SET.size();
                annotationIndex |= LOF_EXTENDED_MASK;
                // If all present, remove consequenceType filter
                if (LOF_EXTENDED_SET.size() == soNames.size()) {
                    // Ensure not filtering by gene, and not combining with other params
                    if (completeIndex && !isValidParam(query, GENE)) {
                        if (simpleCombination(combination)) {
                            query.remove(ANNOT_CONSEQUENCE_TYPE.key());
                        } else if (proteinCodingOnly) {
                            query.remove(ANNOT_CONSEQUENCE_TYPE.key());
                            query.remove(ANNOT_BIOTYPE.key());
                        }
                    }
                }
                if (proteinCodingOnly) {
                    annotationIndex |= LOFE_PROTEIN_CODING_MASK;
                }
            }
            if (soNames.size() == 1 && soNames.get(0).equals(VariantAnnotationUtils.MISSENSE_VARIANT)) {
                ctFilterCoveredBySummary = true;
                annotationIndex |= MISSENSE_VARIANT_MASK;
            }

            // Do not use ctIndex if the CT filter is covered by the summary
            if (!ctFilterCoveredBySummary) {
                boolean ctCovered = completeIndex;
                for (String soName : soNames) {
                    short mask = getMaskFromSoName(soName);
                    if (mask == IndexUtils.EMPTY_MASK) {
                        // If any element is not in the index, do not use this filter
                        consequenceTypeMask = IndexUtils.EMPTY_MASK;
                        ctCovered = false;
                        break;
                    }
                    consequenceTypeMask |= mask;
                    // Some CT filter values are not precise, so the query is not covered.
                    ctCovered &= !isImpreciseCtMask(mask);
                }
                // ConsequenceType filter is covered by index
                if (ctCovered) {
                    if (!isValidParam(query, GENE) && simpleCombination(combination)) {
                        query.remove(ANNOT_CONSEQUENCE_TYPE.key());
                    }
                }
            }
        }

        if (isValidParam(query, ANNOT_BIOTYPE)) {
            // All biotype values are in genes (i.e. non-intergenic)
            intergenic = false;
            boolean biotypeFilterCoveredBySummary = false;
            List<String> biotypes = query.getAsStringList(VariantQueryParam.ANNOT_BIOTYPE.key());
            if (BIOTYPE_SET.containsAll(biotypes)) {
                biotypeFilterCoveredBySummary = BIOTYPE_SET.size() == biotypes.size();
                annotationIndex |= PROTEIN_CODING_MASK;
                // If all present, remove biotype filter
                if (completeIndex && BIOTYPE_SET.size() == biotypes.size()) {
                    // Ensure not filtering by gene, and not combining with other params
                    if (!isValidParam(query, GENE) && simpleCombination(combination)) {
                        query.remove(ANNOT_BIOTYPE.key());
                    }
                }
            }
            if (!biotypeFilterCoveredBySummary) {
                boolean btCovered = completeIndex;
                for (String biotype : biotypes) {
                    byte mask = getMaskFromBiotype(biotype);
                    if (mask == IndexUtils.EMPTY_MASK) {
                        // If any element is not in the index, do not use this filter
                        biotypeMask = IndexUtils.EMPTY_MASK;
                        btCovered = false;
                        break;
                    }
                    biotypeMask |= mask;
                    // Some CT filter values are not precise, so the query is not covered.
                    btCovered &= !isImpreciseBtMask(mask);
                }
                // Biotype filter is covered by index
                if (btCovered) {
                    if (!isValidParam(query, GENE) && simpleCombination(combination)) {
                        query.remove(ANNOT_BIOTYPE.key());
                    }
                }
            }
        }

        // If filter by proteinSubstitution, without filter << or >>, add ProteinCodingMask
        String proteinSubstitution = query.getString(ANNOT_PROTEIN_SUBSTITUTION.key());
        if (StringUtils.isNotEmpty(proteinSubstitution)
                && !proteinSubstitution.contains("<<")
                && !proteinSubstitution.contains(">>")) {
            annotationIndex |= LOF_EXTENDED_MASK;
        }

        List<PopulationFrequencyQuery> popFreqQuery = new ArrayList<>();
        QueryOperation popFreqOp = QueryOperation.AND;
        boolean popFreqPartial = false;
        // TODO: This will skip filters ANNOT_POPULATION_REFERENCE_FREQUENCY and ANNOT_POPULATION_MINNOR_ALLELE_FREQUENCY
        if (isValidParam(query, ANNOT_POPULATION_ALTERNATE_FREQUENCY)) {
            String value = query.getString(VariantQueryParam.ANNOT_POPULATION_ALTERNATE_FREQUENCY.key());
            Pair<QueryOperation, List<String>> pair = VariantQueryUtils.splitValue(value);
            popFreqOp = pair.getKey();

            Set<String> studyPops = new HashSet<>();
            Set<String> popFreqLessThan001 = new HashSet<>();
            List<String> filtersNotCoveredByPopFreqQuery = new ArrayList<>(pair.getValue().size());

            for (String popFreq : pair.getValue()) {
                String[] keyOpValue = VariantQueryUtils.splitOperator(popFreq);
                String studyPop = keyOpValue[0];
                studyPops.add(studyPop);
                double freqFilter = Double.valueOf(keyOpValue[2]);
                if (keyOpValue[1].equals("<") || keyOpValue[1].equals("<<")) {
                    if (freqFilter <= POP_FREQ_THRESHOLD_001) {
                        popFreqLessThan001.add(studyPop);
                    }
                }

                boolean populationInSampleIndex = false;
                boolean populationFilterFullyCovered = false;
                int popFreqIdx = 0;
                for (PopulationFrequencyRange populationRange : configuration.getPopulationRanges()) {
                    if (populationRange.getStudyAndPopulation().equals(studyPop)) {
                        populationInSampleIndex = true;
                        RangeQuery rangeQuery = getRangeQuery(keyOpValue[1], freqFilter, populationRange.getThresholds(),
                                0, 1 + IndexUtils.DELTA);

                        popFreqQuery.add(new PopulationFrequencyQuery(rangeQuery,
                                popFreqIdx, populationRange.getStudy(),
                                populationRange.getPopulation()));
                        populationFilterFullyCovered |= rangeQuery.isExactQuery();
                    }
                    popFreqIdx++;
                }

                if (!populationInSampleIndex) {
                    // If there is any populationFrequency from the query not in the SampleIndex, mark as partial
                    popFreqPartial = true;
                    filtersNotCoveredByPopFreqQuery.add(popFreq);
                } else if (!populationFilterFullyCovered) {
                    filtersNotCoveredByPopFreqQuery.add(popFreq);
                }
            }
            if (QueryOperation.OR.equals(popFreqOp)) {
                // Should use summary popFreq mask?
                if (POP_FREQ_ANY_001_SET.containsAll(popFreqLessThan001) && studyPops.equals(popFreqLessThan001)) {

                    annotationIndex |= POP_FREQ_ANY_001_MASK;

                    if (POP_FREQ_ANY_001_SET.size() == pair.getValue().size()) {
                        // Do not filter using the PopFreq index, as the summary bit covers the filter
                        popFreqQuery.clear();

                        // If the index is complete for all samples, remove the filter from main query
                        if (completeIndex) {
                            query.remove(ANNOT_POPULATION_ALTERNATE_FREQUENCY.key());
                        }
                    }
                }
                if (popFreqPartial) {
                    // Can not use the index with partial OR queries.
                    popFreqQuery.clear();
                } else if (filtersNotCoveredByPopFreqQuery.isEmpty()) {
                    // If all filters are covered, remove filter form query.
                    if (completeIndex) {
                        query.remove(ANNOT_POPULATION_ALTERNATE_FREQUENCY.key());
                    }
                }

            } else {
                popFreqOp = QueryOperation.AND; // it could be null
                // With AND, the query MUST contain ANY popFreq
                for (String s : POP_FREQ_ANY_001_SET) {
                    if (popFreqLessThan001.contains(s)) {
                        annotationIndex |= POP_FREQ_ANY_001_MASK;
                        break;
                    }
                }
                if (completeIndex) {
                    if (filtersNotCoveredByPopFreqQuery.isEmpty()) {
                        query.remove(ANNOT_POPULATION_ALTERNATE_FREQUENCY.key());
                    } else {
                        query.put(ANNOT_POPULATION_ALTERNATE_FREQUENCY.key(),
                                String.join(popFreqOp.separator(), filtersNotCoveredByPopFreqQuery));
                    }
                }
            }
        }

        byte annotationIndexMask = annotationIndex;
        if (intergenic != null) {
            annotationIndexMask |= INTERGENIC_MASK;
            if (intergenic) {
                annotationIndex |= INTERGENIC_MASK;
            }
        }

        if (intergenic == null || intergenic) {
            // If intergenic is undefined, or true, CT and BT filters can not be used.
            consequenceTypeMask = IndexUtils.EMPTY_MASK;
            biotypeMask = IndexUtils.EMPTY_MASK;
        }


        return new SampleAnnotationIndexQuery(new byte[]{annotationIndexMask, annotationIndex}, consequenceTypeMask, biotypeMask,
                popFreqOp, popFreqQuery, popFreqPartial);
    }

    private boolean simpleCombination(BiotypeConsquenceTypeFlagCombination combination) {
        return combination.equals(BiotypeConsquenceTypeFlagCombination.BIOTYPE)
                || combination.equals(BiotypeConsquenceTypeFlagCombination.CT);
    }

    protected RangeQuery getRangeQuery(String op, double value, double[] thresholds) {
        return getRangeQuery(op, value, thresholds, Double.MIN_VALUE, Double.MAX_VALUE);
    }

    protected RangeQuery getRangeQuery(String op, double value, double[] thresholds, double min, double max) {
        double[] range = IndexUtils.queryRange(op, value, min, max);
        return getRangeQuery(range, thresholds, min, max);
    }

    private RangeQuery getRangeQuery(double[] range, double[] thresholds, double min, double max) {
        byte[] rangeCode = IndexUtils.getRangeCodes(range, thresholds);
        boolean exactQuery;
        if (rangeCode[0] == 0) {
            if (rangeCode[1] - 1 == thresholds.length) {
                exactQuery = IndexUtils.equalsTo(range[0], min) && IndexUtils.equalsTo(range[1], max);
            } else {
                exactQuery = IndexUtils.equalsTo(range[1], thresholds[rangeCode[1] - 1]) && IndexUtils.equalsTo(range[0], min);
            }
        } else if (rangeCode[1] - 1 == thresholds.length) {
            exactQuery = IndexUtils.equalsTo(range[0], thresholds[rangeCode[0] - 1]) && IndexUtils.equalsTo(range[1], max);
        } else {
            exactQuery = false;
        }
        return new RangeQuery(
                range[0],
                range[1],
                rangeCode[0],
                rangeCode[1],
                exactQuery
        );
    }

    private static List<String> getAllLoadedGenotypes(StudyMetadata studyMetadata) {
        List<String> allGts = studyMetadata
                .getAttributes()
                .getAsStringList(VariantStorageEngine.Options.LOADED_GENOTYPES.key());
        if (allGts == null || allGts.isEmpty()) {
            allGts = DEFAULT_LOADED_GENOTYPES;
        }
        return allGts;
    }
}
