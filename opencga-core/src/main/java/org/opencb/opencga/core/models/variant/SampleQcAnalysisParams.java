/*
 * Copyright 2015-2020 OpenCB
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

package org.opencb.opencga.core.models.variant;

import org.opencb.opencga.core.tools.ToolParams;

import java.util.List;
import java.util.Map;

public class SampleQcAnalysisParams extends ToolParams {
    public static final String DESCRIPTION = "Sample QC analysis params";
    private String sample;
    private String fastaFile;
    private String baitFile;
    private String targetFile;
    private String variantStatsId;
    private String variantStatsDecription;
    private Map<String, String> variantStatsQuery;
    private String variantStatsJobId;
    private String signatureId;
    private Map<String, String> signatureQuery;
    private String signatureJobId;
    private List<String> genesForCoverageStats;

    private String outdir;

    public SampleQcAnalysisParams() {
    }

    public SampleQcAnalysisParams(String sample, String fastaFile, String baitFile, String targetFile, String variantStatsId,
                                  String variantStatsDecription, Map<String, String> variantStatsQuery, String variantStatsJobId,
                                  String signatureId, Map<String, String> signatureQuery, String signatureJobId,
                                  List<String> genesForCoverageStats, String outdir) {
        this.sample = sample;
        this.fastaFile = fastaFile;
        this.baitFile = baitFile;
        this.targetFile = targetFile;
        this.variantStatsId = variantStatsId;
        this.variantStatsDecription = variantStatsDecription;
        this.variantStatsQuery = variantStatsQuery;
        this.variantStatsJobId = variantStatsJobId;
        this.signatureId = signatureId;
        this.signatureQuery = signatureQuery;
        this.signatureJobId = signatureJobId;
        this.genesForCoverageStats = genesForCoverageStats;
        this.outdir = outdir;
    }

    @Override
    public String toString() {
        final StringBuilder sb = new StringBuilder("SampleQcAnalysisParams{");
        sb.append("sample='").append(sample).append('\'');
        sb.append(", fastaFile='").append(fastaFile).append('\'');
        sb.append(", baitFile='").append(baitFile).append('\'');
        sb.append(", targetFile='").append(targetFile).append('\'');
        sb.append(", variantStatsId='").append(variantStatsId).append('\'');
        sb.append(", variantStatsDecription='").append(variantStatsDecription).append('\'');
        sb.append(", variantStatsQuery=").append(variantStatsQuery);
        sb.append(", variantStatsJobId='").append(variantStatsJobId).append('\'');
        sb.append(", signatureId='").append(signatureId).append('\'');
        sb.append(", signatureQuery=").append(signatureQuery);
        sb.append(", signatureJobId='").append(signatureJobId).append('\'');
        sb.append(", genesForCoverageStats=").append(genesForCoverageStats);
        sb.append(", outdir='").append(outdir).append('\'');
        sb.append('}');
        return sb.toString();
    }

    public String getSample() {
        return sample;
    }

    public SampleQcAnalysisParams setSample(String sample) {
        this.sample = sample;
        return this;
    }

    public String getFastaFile() {
        return fastaFile;
    }

    public SampleQcAnalysisParams setFastaFile(String fastaFile) {
        this.fastaFile = fastaFile;
        return this;
    }

    public String getBaitFile() {
        return baitFile;
    }

    public SampleQcAnalysisParams setBaitFile(String baitFile) {
        this.baitFile = baitFile;
        return this;
    }

    public String getTargetFile() {
        return targetFile;
    }

    public SampleQcAnalysisParams setTargetFile(String targetFile) {
        this.targetFile = targetFile;
        return this;
    }

    public String getVariantStatsId() {
        return variantStatsId;
    }

    public SampleQcAnalysisParams setVariantStatsId(String variantStatsId) {
        this.variantStatsId = variantStatsId;
        return this;
    }

    public String getVariantStatsDecription() {
        return variantStatsDecription;
    }

    public SampleQcAnalysisParams setVariantStatsDecription(String variantStatsDecription) {
        this.variantStatsDecription = variantStatsDecription;
        return this;
    }

    public Map<String, String> getVariantStatsQuery() {
        return variantStatsQuery;
    }

    public SampleQcAnalysisParams setVariantStatsQuery(Map<String, String> variantStatsQuery) {
        this.variantStatsQuery = variantStatsQuery;
        return this;
    }

    public String getVariantStatsJobId() {
        return variantStatsJobId;
    }

    public SampleQcAnalysisParams setVariantStatsJobId(String variantStatsJobId) {
        this.variantStatsJobId = variantStatsJobId;
        return this;
    }

    public String getSignatureId() {
        return signatureId;
    }

    public SampleQcAnalysisParams setSignatureId(String signatureId) {
        this.signatureId = signatureId;
        return this;
    }

    public Map<String, String> getSignatureQuery() {
        return signatureQuery;
    }

    public SampleQcAnalysisParams setSignatureQuery(Map<String, String> signatureQuery) {
        this.signatureQuery = signatureQuery;
        return this;
    }

    public String getSignatureJobId() {
        return signatureJobId;
    }

    public SampleQcAnalysisParams setSignatureJobId(String signatureJobId) {
        this.signatureJobId = signatureJobId;
        return this;
    }

    public List<String> getGenesForCoverageStats() {
        return genesForCoverageStats;
    }

    public SampleQcAnalysisParams setGenesForCoverageStats(List<String> genesForCoverageStats) {
        this.genesForCoverageStats = genesForCoverageStats;
        return this;
    }

    public String getOutdir() {
        return outdir;
    }

    public SampleQcAnalysisParams setOutdir(String outdir) {
        this.outdir = outdir;
        return this;
    }
}
