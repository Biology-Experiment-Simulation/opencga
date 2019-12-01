package org.opencb.opencga.analysis.clinical;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectReader;
import com.fasterxml.jackson.databind.ObjectWriter;
import org.apache.commons.collections.CollectionUtils;
import org.apache.commons.io.FileUtils;
import org.apache.commons.io.LineIterator;
import org.apache.commons.lang.StringUtils;
import org.opencb.biodata.models.clinical.interpretation.DiseasePanel;
import org.opencb.biodata.models.clinical.interpretation.Interpretation;
import org.opencb.biodata.models.clinical.interpretation.ReportedVariant;
import org.opencb.biodata.models.clinical.interpretation.exceptions.InterpretationAnalysisException;
import org.opencb.biodata.models.clinical.pedigree.Member;
import org.opencb.biodata.models.clinical.pedigree.Pedigree;
import org.opencb.biodata.models.variant.Variant;
import org.opencb.biodata.tools.clinical.ReportedVariantCreator;
import org.opencb.commons.utils.ListUtils;
import org.opencb.opencga.core.common.JacksonUtils;
import org.opencb.opencga.core.exception.AnalysisException;
import org.opencb.opencga.core.models.ClinicalAnalysis;
import org.opencb.opencga.core.models.Family;
import org.opencb.opencga.core.models.Individual;

import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.PrintWriter;
import java.nio.file.Path;
import java.util.*;

import static org.opencb.biodata.models.clinical.interpretation.ClinicalProperty.ModeOfInheritance.COMPOUND_HETEROZYGOUS;

public class ClinicalUtils {

    public static final String INCLUDE_LOW_COVERAGE_PARAM = "includeLowCoverage";
    public static final String MAX_LOW_COVERAGE_PARAM = "maxLowCoverage";
    public static final String SKIP_DIAGNOSTIC_VARIANTS_PARAM = "skipDiagnosticVariants";
    public static final String SKIP_UNTIERED_VARIANTS_PARAM = "skipUntieredVariants";
    public static final int LOW_COVERAGE_DEFAULT = 20;
    public static final int DEFAULT_COVERAGE_THRESHOLD = 20;

    public static Individual getProband(ClinicalAnalysis clinicalAnalysis) throws AnalysisException {
        Individual proband = clinicalAnalysis.getProband();

        String clinicalAnalysisId = clinicalAnalysis.getId();
        // Sanity checks
        if (proband == null) {
            throw new AnalysisException("Missing proband in clinical analysis " + clinicalAnalysisId);
        }

        if (ListUtils.isEmpty(proband.getSamples())) {
            throw new AnalysisException("Missing samples in proband " + proband.getId() + " in clinical analysis " + clinicalAnalysisId);
        }

        if (proband.getSamples().size() > 1) {
            throw new AnalysisException("Found more than one sample for proband " + proband.getId() + " in clinical analysis "
                    + clinicalAnalysisId);
        }

        // Fill with parent information
        String fatherId = null;
        String motherId = null;
        if (proband.getFather() != null && StringUtils.isNotEmpty(proband.getFather().getId())) {
            fatherId = proband.getFather().getId();
        }
        if (proband.getMother() != null && StringUtils.isNotEmpty(proband.getMother().getId())) {
            motherId = proband.getMother().getId();
        }
        if (fatherId != null && motherId != null && clinicalAnalysis.getFamily() != null
                && ListUtils.isNotEmpty(clinicalAnalysis.getFamily().getMembers())) {
            for (Individual member : clinicalAnalysis.getFamily().getMembers()) {
                if (member.getId().equals(fatherId)) {
                    proband.setFather(member);
                } else if (member.getId().equals(motherId)) {
                    proband.setMother(member);
                }
            }
        }

        return proband;
    }

    public List<String> getSampleNames(ClinicalAnalysis clinicalAnalysis) throws AnalysisException {
        return getSampleNames(clinicalAnalysis, null);
    }

    public static List<String> getSampleNames(ClinicalAnalysis clinicalAnalysis, Individual proband) throws AnalysisException {
        List<String> sampleList = new ArrayList<>();
        // Sanity check
        if (clinicalAnalysis != null && clinicalAnalysis.getFamily() != null
                && CollectionUtils.isNotEmpty(clinicalAnalysis.getFamily().getMembers())) {

            Map<String, Individual> individualMap = new HashMap<>();
            for (Individual member : clinicalAnalysis.getFamily().getMembers()) {
                if (ListUtils.isEmpty(member.getSamples())) {
//                    throw new AnalysisException("No samples found for member " + member.getId());
                    continue;
                }
                if (member.getSamples().size() > 1) {
                    throw new AnalysisException("More than one sample found for member " + member.getId());
                }
                sampleList.add(member.getSamples().get(0).getId());
                individualMap.put(member.getId(), member);
            }

            if (proband != null) {
                // Fill proband information to be able to navigate to the parents and their samples easily
                // Sanity check
                if (proband.getFather() != null && StringUtils.isNotEmpty(proband.getFather().getId())
                        && individualMap.containsKey(proband.getFather().getId())) {
                    proband.setFather(individualMap.get(proband.getFather().getId()));
                }
                if (proband.getMother() != null && StringUtils.isNotEmpty(proband.getMother().getId())
                        && individualMap.containsKey(proband.getMother().getId())) {
                    proband.setMother(individualMap.get(proband.getMother().getId()));
                }
            }
        }
        return sampleList;
    }

//    public static List<DiseasePanel> getDiseasePanelsFromIds(List<String> diseasePanelIds, String studyId, CatalogManager catalogManager,
//                                                             String sessionId) throws AnalysisException {
//        List<DiseasePanel> diseasePanels = new ArrayList<>();
//        if (CollectionUtils.isEmpty(diseasePanelIds)) {
//            throw new AnalysisException("Missing disease panels");
//        }
//
//        OpenCGAResult<Panel> queryResults;
//        try {
//            queryResults = catalogManager.getPanelManager().get(studyId, diseasePanelIds, QueryOptions.empty(), sessionId);
//        } catch (CatalogException e) {
//            throw new AnalysisException(e.getMessage(), e);
//        }
//
//        if (queryResults.getNumResults() != diseasePanelIds.size()) {
//            throw new AnalysisException("The number of disease panels retrieved doesn't match the number of disease panels queried");
//        }
//
//        for (Panel queryResult : queryResults.getResults()) {
//            if (queryResult.getNumResults() != 1) {
//                throw new AnalysisException("The number of disease panels retrieved doesn't match the number of disease panels "
//                        + "queried");
//            }
//            diseasePanels.add(queryResult.first());
//        }
//
//        return diseasePanels;
//    }

    public static List<DiseasePanel.VariantPanel> getDiagnosticVariants(List<DiseasePanel> diseasePanels) {
        List<DiseasePanel.VariantPanel> diagnosticVariants = new ArrayList<>();
        for (DiseasePanel diseasePanel : diseasePanels) {
            if (diseasePanel != null && CollectionUtils.isNotEmpty(diseasePanel.getVariants())) {
                diagnosticVariants.addAll(diseasePanel.getVariants());
            }
        }
        return diagnosticVariants;
    }

    public static List<String> getGeneIds(List<DiseasePanel> diseasePanels) {
        List<String> geneIds = new ArrayList<>();

        if (CollectionUtils.isNotEmpty(diseasePanels)) {
            for (DiseasePanel diseasePanel : diseasePanels) {
                if (diseasePanel != null && CollectionUtils.isNotEmpty(diseasePanel.getGenes())) {
                    for (DiseasePanel.GenePanel gene : diseasePanel.getGenes()) {
                        geneIds.add(gene.getId());
                    }
                }
            }
        }
        return geneIds;
    }

    public static Map<String, String> getSampleMap(ClinicalAnalysis clinicalAnalysis, Individual proband) throws AnalysisException {
        Map<String, String> individualSampleMap = new HashMap<>();
        // Sanity check
        if (clinicalAnalysis != null && clinicalAnalysis.getFamily() != null
                && CollectionUtils.isNotEmpty(clinicalAnalysis.getFamily().getMembers())) {

            Map<String, Individual> individualMap = new HashMap<>();
            for (Individual member : clinicalAnalysis.getFamily().getMembers()) {
                if (ListUtils.isEmpty(member.getSamples())) {
//                    throw new AnalysisException("No samples found for member " + member.getId());
                    continue;
                }
                if (member.getSamples().size() > 1) {
                    throw new AnalysisException("More than one sample found for member " + member.getId());
                }
                individualSampleMap.put(member.getId(), member.getSamples().get(0).getId());
                individualMap.put(member.getId(), member);
            }

            if (proband != null) {
                // Fill proband information to be able to navigate to the parents and their samples easily
                // Sanity check
                if (proband.getFather() != null && StringUtils.isNotEmpty(proband.getFather().getId())
                        && individualMap.containsKey(proband.getFather().getId())) {
                    proband.setFather(individualMap.get(proband.getFather().getId()));
                }
                if (proband.getMother() != null && StringUtils.isNotEmpty(proband.getMother().getId())
                        && individualMap.containsKey(proband.getMother().getId())) {
                    proband.setMother(individualMap.get(proband.getMother().getId()));
                }
            }
        }
        return individualSampleMap;
    }

    public static void removeMembersWithoutSamples(Pedigree pedigree, Family family) {
        Set<String> membersWithoutSamples = new HashSet<>();
        for (Individual member : family.getMembers()) {
            if (ListUtils.isEmpty(member.getSamples())) {
                membersWithoutSamples.add(member.getId());
            }
        }

        Iterator<Member> iterator = pedigree.getMembers().iterator();
        while (iterator.hasNext()) {
            Member member = iterator.next();
            if (membersWithoutSamples.contains(member.getId())) {
                iterator.remove();
            } else {
                if (member.getFather() != null && membersWithoutSamples.contains(member.getFather().getId())) {
                    member.setFather(null);
                }
                if (member.getMother() != null && membersWithoutSamples.contains(member.getMother().getId())) {
                    member.setMother(null);
                }
            }
        }

        if (pedigree.getProband().getFather() != null && membersWithoutSamples.contains(pedigree.getProband().getFather().getId())) {
            pedigree.getProband().setFather(null);
        }
        if (pedigree.getProband().getMother() != null && membersWithoutSamples.contains(pedigree.getProband().getMother().getId())) {
            pedigree.getProband().setMother(null);
        }
    }

    public static List<ReportedVariant> getCompoundHeterozygousReportedVariants(Map<String, List<Variant>> chVariantMap,
                                                                                ReportedVariantCreator creator)
            throws InterpretationAnalysisException {
        // Compound heterozygous management
        // Create transcript - reported variant map from transcript - variant
        Map<String, List<ReportedVariant>> reportedVariantMap = new HashMap<>();
        for (Map.Entry<String, List<Variant>> entry : chVariantMap.entrySet()) {
            reportedVariantMap.put(entry.getKey(), creator.create(entry.getValue(), COMPOUND_HETEROZYGOUS));
        }
        return creator.groupCHVariants(reportedVariantMap);
    }

    public static List<ReportedVariant> readReportedVariants(Path path) throws AnalysisException {
        List<ReportedVariant> reportedVariants = new ArrayList<>();
        if (path != null || path.toFile().exists()) {
            try {
                ObjectReader objReader = JacksonUtils.getDefaultObjectMapper().readerFor(ReportedVariant.class);
                LineIterator lineIterator = FileUtils.lineIterator(path.toFile());
                while (lineIterator.hasNext()) {
                    reportedVariants.add(objReader.readValue(lineIterator.next()));
                }
            } catch (IOException e) {
                throw new AnalysisException("Error reading reported variants from file: " + path, e);
            }
        }
        return reportedVariants;
    }

    public static void writeReportedVariants(List<ReportedVariant> reportedVariants, Path path) throws AnalysisException {
        // Write primary findings
        try {
            PrintWriter pw = new PrintWriter(path.toFile());
            if (CollectionUtils.isNotEmpty(reportedVariants)) {
                ObjectWriter objectWriter = JacksonUtils.getDefaultObjectMapper().writerFor(ReportedVariant.class);
                for (ReportedVariant primaryFinding : reportedVariants) {
                    pw.println(objectWriter.writeValueAsString(primaryFinding));
                }
            }
            pw.close();
        } catch (FileNotFoundException | JsonProcessingException e) {
            throw new AnalysisException("Error writing reported variants to file: " + path, e);
        }
    }

    public static Interpretation readInterpretation(Path path) throws AnalysisException {
        if (path != null || path.toFile().exists()) {
            try {
                ObjectReader objReader = JacksonUtils.getDefaultObjectMapper().readerFor(Interpretation.class);
                return objReader.readValue(path.toFile());
            } catch (IOException e) {
                throw new AnalysisException("Error reading interpretation from file: " + path, e);
            }
        }
        return null;
    }
}