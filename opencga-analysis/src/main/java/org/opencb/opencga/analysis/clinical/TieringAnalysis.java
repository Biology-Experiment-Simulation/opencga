/*
 * Copyright 2015-2017 OpenCB
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.opencb.opencga.analysis.clinical;

import org.apache.commons.lang3.StringUtils;
import org.opencb.biodata.models.commons.Analyst;
import org.opencb.biodata.models.commons.Phenotype;
import org.opencb.biodata.models.commons.Software;
import org.opencb.biodata.models.core.pedigree.Individual;
import org.opencb.biodata.models.core.pedigree.Pedigree;
import org.opencb.biodata.models.variant.Variant;
import org.opencb.biodata.models.variant.avro.ConsequenceType;
import org.opencb.biodata.tools.pedigree.ModeOfInheritance;
import org.opencb.commons.datastore.core.ObjectMap;
import org.opencb.commons.datastore.core.Query;
import org.opencb.commons.datastore.core.QueryOptions;
import org.opencb.commons.datastore.core.QueryResult;
import org.opencb.commons.utils.ListUtils;
import org.opencb.opencga.analysis.AnalysisResult;
import org.opencb.opencga.analysis.OpenCgaAnalysis;
import org.opencb.opencga.analysis.exceptions.AnalysisException;
import org.opencb.opencga.catalog.db.api.UserDBAdaptor;
import org.opencb.opencga.core.common.TimeUtils;
import org.opencb.opencga.core.models.ClinicalAnalysis;
import org.opencb.opencga.core.models.DiseasePanel;
import org.opencb.opencga.core.models.Family;
import org.opencb.opencga.core.models.User;
import org.opencb.opencga.core.models.clinical.GenomicFeature;
import org.opencb.opencga.core.models.clinical.Interpretation;
import org.opencb.opencga.core.models.clinical.ReportedEvent;
import org.opencb.opencga.core.models.clinical.ReportedVariant;
import org.opencb.opencga.core.results.VariantQueryResult;
import org.opencb.opencga.storage.core.variant.adaptors.VariantQueryParam;

import java.util.*;
import java.util.stream.Collectors;

public class TieringAnalysis extends OpenCgaAnalysis<Interpretation> {

    private String clinicalAnalysisId;
    private List<String> diseasePanelIds;

    public TieringAnalysis(String opencgaHome, String studyStr, String token) {
        super(opencgaHome, studyStr, token);
    }

    public TieringAnalysis(String opencgaHome, String studyStr, String token, String clinicalAnalysisId,
                           List<String> diseasePanelIds, ObjectMap config) {
        super(opencgaHome, studyStr, token);

        this.clinicalAnalysisId = clinicalAnalysisId;
        this.diseasePanelIds = diseasePanelIds;
    }

    @Override
    public AnalysisResult<Interpretation> execute() throws Exception {
        // checks

        // set defaults

        QueryResult<ClinicalAnalysis> clinicalAnalysisQueryResult = catalogManager.getClinicalAnalysisManager().get(studyStr,
                clinicalAnalysisId, QueryOptions.empty(), token);
        if (clinicalAnalysisQueryResult.getNumResults() == 0) {
            throw new AnalysisException("Clinical analysis " + clinicalAnalysisId + " not found in study " + studyStr);
        }

        ClinicalAnalysis clinicalAnalysis = clinicalAnalysisQueryResult.first();

        if (clinicalAnalysis.getFamily() == null || StringUtils.isEmpty(clinicalAnalysis.getFamily().getId())) {
            throw new AnalysisException("Missing family in clinical analysis " + clinicalAnalysisId);
        }

        // TODO: Do we have to raise an exception if no disease panels are provided?
        List<DiseasePanel> diseasePanels = new ArrayList<>();
        if (diseasePanelIds != null && !diseasePanelIds.isEmpty()) {
            List<QueryResult<DiseasePanel>> queryResults = catalogManager.getDiseasePanelManager()
                    .get(studyStr, diseasePanelIds, new Query(), QueryOptions.empty(), token);

            if (queryResults.size() != diseasePanelIds.size()) {
                throw new AnalysisException("The number of disease panels retrieved doesn't match the number of disease panels queried");
            }

            for (QueryResult<DiseasePanel> queryResult : queryResults) {
                if (queryResult.getNumResults() != 1) {
                    throw new AnalysisException("The number of disease panels retrieved doesn't match the number of disease panels " +
                            "queried");
                }
                diseasePanels.add(queryResult.first());
            }
        }

//        diseasePanels.get(0).getGenes().get(0).getId()

        // Check sample and proband exists

        Pedigree pedigree = getPedigreeFromFamily(clinicalAnalysis.getFamily());
        List<Phenotype> phenotypes = clinicalAnalysis.getProband().getPhenotypes();

        // Query with the filters: genotypes, popFreq < 0.01, biotype = protein_coding, genes
        Query query = new Query()
                .append(VariantQueryParam.ANNOT_BIOTYPE.key(), "protein_coding")
                .append(VariantQueryParam.ANNOT_POPULATION_ALTERNATE_FREQUENCY.key(), "<0.01");

        Map<String, List<String>> genotypes;
        VariantQueryResult<Variant> variantQueryResult;
        Map<String, ReportedVariant> reportedVariantMap = new HashMap<>();

        ReportedEvent.Penetrance penetrance = ReportedEvent.Penetrance.COMPLETE;
        boolean penetranceBoolean = penetrance == ReportedEvent.Penetrance.COMPLETE;

        for (Phenotype phenotype : phenotypes) {
            for (DiseasePanel diseasePanel: diseasePanels) {
                // Genes
                query.put(VariantQueryParam.ANNOT_XREF.key(), diseasePanel.getGenes()
                        .stream()
                        .map(DiseasePanel.GenePanel::getId)
                        .collect(Collectors.toList()));

                // ---- dominant -----

                // Genotypes following the format: {sample_1}:{gt_1}(,{gt_n})*(;{sample_n}:{gt_1}(,{gt_n})*)*
                genotypes = ModeOfInheritance.dominant(pedigree, phenotype, penetranceBoolean);
                putGenotypes(genotypes, query);
                variantQueryResult = variantStorageManager.get(query, QueryOptions.empty(), token);
                generateReportedVariants(variantQueryResult, phenotype, diseasePanel, ReportedEvent.ReportedModeOfInheritance.MONOALLELIC,
                        penetrance, reportedVariantMap);

                // ---- recessive -----

                genotypes = ModeOfInheritance.recessive(pedigree, phenotype, penetranceBoolean);
                putGenotypes(genotypes, query);
                variantQueryResult = variantStorageManager.get(query, QueryOptions.empty(), token);
                generateReportedVariants(variantQueryResult, phenotype, diseasePanel, ReportedEvent.ReportedModeOfInheritance.BIALLELIC,
                        penetrance, reportedVariantMap);

                // ---- xLinked -----

                genotypes = ModeOfInheritance.xLinked(pedigree, phenotype, true);
                putGenotypes(genotypes, query);
                variantQueryResult = variantStorageManager.get(query, QueryOptions.empty(), token);
                generateReportedVariants(variantQueryResult, phenotype, diseasePanel,
                        ReportedEvent.ReportedModeOfInheritance.XLINKED_MONOALLELIC, penetrance, reportedVariantMap);

                genotypes = ModeOfInheritance.xLinked(pedigree, phenotype, false);
                putGenotypes(genotypes, query);
                variantQueryResult = variantStorageManager.get(query, QueryOptions.empty(), token);
                generateReportedVariants(variantQueryResult, phenotype, diseasePanel,
                        ReportedEvent.ReportedModeOfInheritance.XLINKED_BIALLELIC, penetrance, reportedVariantMap);

                // ---- yLinked -----

                genotypes = ModeOfInheritance.yLinked(pedigree, phenotype);
                putGenotypes(genotypes, query);
                variantQueryResult = variantStorageManager.get(query, QueryOptions.empty(), token);
                // TODO: ReportedModeOfInheritance ???
                generateReportedVariants(variantQueryResult, phenotype, diseasePanel,
                        ReportedEvent.ReportedModeOfInheritance.UNKNOWN, penetrance, reportedVariantMap);

                // TODO: additional MoI, i.e.: deNovo, compound heterozigous
            }
        }

        // TODO: take into account BAM coverage

        String userId = catalogManager.getUserManager().getUserId(token);
        QueryResult<User> userQueryResult = catalogManager.getUserManager().get(userId, new QueryOptions(QueryOptions.INCLUDE,
                Arrays.asList(UserDBAdaptor.QueryParams.EMAIL.key(), UserDBAdaptor.QueryParams.ORGANIZATION.key())), token);

        // Create Interpretation
        Interpretation interpretation = new Interpretation()
                .setId("JT-PF-007")
                .setAnalyst(new Analyst(userId, userQueryResult.first().getEmail(), userQueryResult.first().getOrganization()))
                .setClinicalAnalysisId(clinicalAnalysisId)
                .setCreationDate(TimeUtils.getTime())
                .setPanels(diseasePanels)
                .setFilters(null) //TODO
                .setSoftware(new Software().setName("Tiering"))
                .setReportedVariants(new ArrayList<>(reportedVariantMap.values()));

        // Return interpretation result
        return new AnalysisResult<>(interpretation);
    }

    private void putGenotypes(Map<String, List<String>> genotypes, Query query) {
        query.put(VariantQueryParam.GENOTYPE.key(),
                StringUtils.join(genotypes.entrySet().stream()
                        .map(entry -> entry.getKey() + ":" + StringUtils.join(entry.getValue(), ","))
                        .collect(Collectors.toList()), ";"));

    }

    private void generateReportedVariants(VariantQueryResult<Variant> variantQueryResult, Phenotype phenotype, DiseasePanel diseasePanel,
                                          ReportedEvent.ReportedModeOfInheritance moi, ReportedEvent.Penetrance penetrance,
                                          Map<String, ReportedVariant> reportedVariantMap) {
        for (Variant variant: variantQueryResult.getResult()) {
            if (!reportedVariantMap.containsKey(variant.getId())) {
                reportedVariantMap.put(variant.getId(), new ReportedVariant(variant.getImpl(), 0, new ArrayList<>(),
                        Collections.emptyList(), Collections.emptyMap()));
            }
            ReportedVariant reportedVariant = reportedVariantMap.get(variant.getId());

            // Sanity check
            if (variant.getAnnotation() != null && ListUtils.isNotEmpty(variant.getAnnotation().getConsequenceTypes())) {
                for (ConsequenceType ct: variant.getAnnotation().getConsequenceTypes()) {
                    // Create the reported event
                    ReportedEvent reportedEvent = new ReportedEvent()
                            .setId("JT-PF-" + reportedVariant.getReportedEvents().size())
                            .setPhenotypes(Collections.singletonList(phenotype))
                            .setConsequenceTypeIds(Collections.singletonList(ct.getBiotype()))
                            .setGenomicFeature(new GenomicFeature(ct.getEnsemblGeneId(), ct.getEnsemblTranscriptId(), ct.getGeneName(),
                                    null, null))
                            .setModeOfInheritance(moi)
                            .setPanelId(diseasePanel.getId())
                            .setPenetrance(penetrance);

                    // TODO: add additional reported event fields

                    // Add reported event to the reported variant
                    reportedVariant.getReportedEvents().add(reportedEvent);
                }
            }
        }

    }

    private Pedigree getPedigreeFromFamily(Family family) {
        List<Individual> individuals = parseMembersToBiodataIndividuals(family.getMembers());
        return new Pedigree(family.getId(), individuals, family.getPhenotypes(), family.getAttributes());
    }

    private List<Individual> parseMembersToBiodataIndividuals(List<org.opencb.opencga.core.models.Individual> members) {
        Map<String, Individual> individualMap = new HashMap();

        // Parse all the individuals
        for (org.opencb.opencga.core.models.Individual member : members) {
            Individual individual = new Individual(member.getId(), member.getName(), null, null, member.getMultiples(),
                    Individual.Sex.getEnum(member.getSex().toString()), member.getLifeStatus(),
                    Individual.AffectionStatus.getEnum(member.getAffectationStatus().toString()), member.getPhenotypes(),
                    member.getAttributes());
            individualMap.put(individual.getId(), individual);
        }

        // Fill parent information
        for (org.opencb.opencga.core.models.Individual member : members) {
            if (member.getFather() != null && StringUtils.isNotEmpty(member.getFather().getId())) {
                individualMap.get(member.getId()).setFather(individualMap.get(member.getFather().getId()));
            }
            if (member.getMother() != null && StringUtils.isNotEmpty(member.getMother().getId())) {
                individualMap.get(member.getId()).setMother(individualMap.get(member.getMother().getId()));
            }
        }

        return new ArrayList<>(individualMap.values());
    }

    private List<ReportedVariant> dominant() {
        return null;
    }

    private List<ReportedVariant> recessive() {
        // MoI -> genotypes
        // Variant Query query -> (biotype, gene, genoptype)
        // Iterator for (Var) -> getReportedEvents(rv)
        // create RV
        return null;
    }


    private List<ReportedEvent> getReportedEvents(Variant variant) {
        return null;
    }

    private Interpretation createInterpretation() {
        return null;
    }
}
