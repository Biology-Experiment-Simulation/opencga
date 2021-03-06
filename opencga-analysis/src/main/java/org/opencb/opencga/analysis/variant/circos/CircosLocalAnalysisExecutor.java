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

package org.opencb.opencga.analysis.variant.circos;

import org.apache.commons.collections.CollectionUtils;
import org.apache.commons.collections.MapUtils;
import org.apache.commons.lang3.time.StopWatch;
import org.opencb.biodata.models.variant.Variant;
import org.opencb.biodata.models.variant.avro.BreakendMate;
import org.opencb.biodata.models.variant.avro.StructuralVariantType;
import org.opencb.biodata.models.variant.avro.StructuralVariation;
import org.opencb.commons.datastore.core.Query;
import org.opencb.commons.datastore.core.QueryOptions;
import org.opencb.commons.utils.DockerUtils;
import org.opencb.opencga.analysis.StorageToolExecutor;
import org.opencb.opencga.analysis.variant.manager.VariantStorageManager;
import org.opencb.opencga.core.common.TimeUtils;
import org.opencb.opencga.core.exceptions.ToolException;
import org.opencb.opencga.core.models.variant.CircosAnalysisParams;
import org.opencb.opencga.core.models.variant.CircosTrack;
import org.opencb.opencga.core.tools.annotations.ToolExecutor;
import org.opencb.opencga.core.tools.variant.CircosAnalysisExecutor;
import org.opencb.opencga.storage.core.variant.adaptors.iterators.VariantDBIterator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.IOException;
import java.io.PrintWriter;
import java.util.*;
import java.util.concurrent.*;

import static org.opencb.opencga.analysis.wrappers.OpenCgaWrapperAnalysis.DOCKER_INPUT_PATH;
import static org.opencb.opencga.analysis.wrappers.OpenCgaWrapperAnalysis.DOCKER_OUTPUT_PATH;
import static org.opencb.opencga.storage.core.variant.adaptors.VariantQueryParam.STUDY;

@ToolExecutor(id="opencga-local", tool = CircosAnalysis.ID,
        framework = ToolExecutor.Framework.LOCAL, source = ToolExecutor.Source.STORAGE)
public class CircosLocalAnalysisExecutor extends CircosAnalysisExecutor implements StorageToolExecutor {

    public final static String R_DOCKER_IMAGE = "opencb/opencga-r:2.0.0-rc1";

    private Query query;

    private File snvsFile;
    private File rearrsFile;
    private File indelsFile;
    private File cnvsFile;

    private boolean plotCopynumber = false;
    private boolean plotIndels = false;
    private boolean plotRearrangements = false;

    private Logger logger = LoggerFactory.getLogger(this.getClass());

    public CircosLocalAnalysisExecutor() {
        super();
    }

    public CircosLocalAnalysisExecutor(String study, CircosAnalysisParams params) {
        super(study, params);
    }

    @Override
    public void run() throws ToolException, IOException {

        // Create query
        Query query = new Query();
        if (MapUtils.isNotEmpty(getCircosParams().getQuery())) {
            query.putAll(getCircosParams().getQuery());
        }
        query.put(STUDY.key(), getStudy());

        // Launch a thread per query
        VariantStorageManager storageManager = getVariantStorageManager();

        ExecutorService threadPool = Executors.newFixedThreadPool(4);

        List<Future<Boolean>> futureList = new ArrayList<>(4);
        futureList.add(threadPool.submit(getNamedThread("SNV", () -> snvQuery(query, storageManager))));
        futureList.add(threadPool.submit(getNamedThread("COPY_NUMBER", () -> copyNumberQuery(query, storageManager))));
        futureList.add(threadPool.submit(getNamedThread("INDEL", ()-> indelQuery(query, storageManager))));
        futureList.add(threadPool.submit(getNamedThread("REARRANGEMENT", () -> rearrangementQuery(query, storageManager))));

        threadPool.shutdown();

        try {
            threadPool.awaitTermination(2, TimeUnit.MINUTES);
            if (!threadPool.isTerminated()) {
                for (Future<Boolean> future : futureList) {
                    future.cancel(true);
                }
            }
        } catch (InterruptedException e) {
            throw new ToolException("Error launching threads when executing the Cisco analysis", e);
        }


        // Execute R script
        // circos.R ./snvs.tsv ./indels.tsv ./cnvs.tsv ./rearrs.tsv SampleId
        String rScriptPath = getExecutorParams().getString("opencgaHome") + "/analysis/R/" + getToolId();
        List<AbstractMap.SimpleEntry<String, String>> inputBindings = new ArrayList<>();
        inputBindings.add(new AbstractMap.SimpleEntry<>(rScriptPath, DOCKER_INPUT_PATH));
        AbstractMap.SimpleEntry<String, String> outputBinding = new AbstractMap.SimpleEntry<>(getOutDir().toAbsolutePath().toString(),
                DOCKER_OUTPUT_PATH);
        String scriptParams = "R CMD Rscript --vanilla " + DOCKER_INPUT_PATH + "/circos.R"
                + (plotCopynumber ? "" : " --no_copynumber")
                + (plotIndels ? "" : " --no_indels")
                + (plotRearrangements ? "" : " --no_rearrangements")
                + " --out_path " + DOCKER_OUTPUT_PATH
                + " " + DOCKER_OUTPUT_PATH + "/" + snvsFile.getName()
                + " " + DOCKER_OUTPUT_PATH + "/" + indelsFile.getName()
                + " " + DOCKER_OUTPUT_PATH + "/" + cnvsFile.getName()
                + " " + DOCKER_OUTPUT_PATH + "/" + rearrsFile.getName()
                + " " + getCircosParams().getTitle();

        StopWatch stopWatch = StopWatch.createStarted();
        String cmdline = DockerUtils.run(R_DOCKER_IMAGE, inputBindings, outputBinding, scriptParams, null);
        logger.info("Docker command line: " + cmdline);
        logger.info("Execution time: " + TimeUtils.durationToString(stopWatch));
    }

    /**
     * Create file with SNV variants.
     *
     * @param query General query
     * @param storageManager    Variant storage manager
     * @return True or false depending on successs
     */
    private boolean snvQuery(Query query, VariantStorageManager storageManager) {
        try {
            snvsFile = getOutDir().resolve("snvs.tsv").toFile();
            PrintWriter pw = new PrintWriter(snvsFile);
            pw.println("Chromosome\tchromStart\tchromEnd\tref\talt\tlogDistPrev");

            CircosTrack snvTrack = getCircosParams().getCircosTrackByType("SNV");
            if (snvTrack == null) {
                throw new ToolException("Missing SNV track");
            }

            int threshold;

            switch (getCircosParams().getDensity()) {
                case "HIGH":
                    threshold = Integer.MAX_VALUE;
                    break;
                case "MEDIUM":
                    threshold = 250000;
                    break;
                case "LOW":
                default:
                    threshold = 100000;
                    break;
            }

            Map<String, String> trackQuery = checkTrackQuery(snvTrack);

            Query snvQuery = new Query(query);
            snvQuery.putAll(trackQuery);

            QueryOptions queryOptions = new QueryOptions()
                    .append(QueryOptions.INCLUDE, "id")
                    .append(QueryOptions.SORT, true);

            logger.info("SNV track, query: " + snvQuery.toJson());
            logger.info("SNV track, query options: " + queryOptions.toJson());

            VariantDBIterator iterator = storageManager.iterator(snvQuery, queryOptions, getToken());

            int prevStart = 0;
            String currentChrom = "";
            while (iterator.hasNext()) {
                Variant v = iterator.next();
                if (v.getStart() > v.getEnd()) {
                    // Sanity check
                    addWarning("Skipping variant " + v.toString() + ", start = " + v.getStart() + ", end = " + v.getEnd());
                } else {
                    if (!v.getChromosome().equals(currentChrom)) {
                        prevStart = 0;
                        currentChrom = v.getChromosome();
                    }
                    int dist = v.getStart() - prevStart;
                    if (dist < threshold) {
                        pw.println("chr" + v.getChromosome() + "\t" + v.getStart() + "\t" + v.getEnd() + "\t" + v.getReference() + "\t"
                                + v.getAlternate() + "\t" + Math.log10(dist));
                    }
                    prevStart = v.getStart();
                }
            }

            pw.close();
        } catch(Exception e){
            return false;
        }
        return true;
    }

    /**
     * Create file with copy-number variants.
     *
     * @param query General query
     * @param storageManager    Variant storage manager
     * @return True or false depending on successs
     */
    private boolean copyNumberQuery(Query query, VariantStorageManager storageManager) {
        try {
            cnvsFile = getOutDir().resolve("cnvs.tsv").toFile();
            PrintWriter pw = new PrintWriter(cnvsFile);
            pw.println("Chromosome\tchromStart\tchromEnd\tlabel\tmajorCopyNumber\tminorCopyNumber");

            CircosTrack copyNumberTrack = getCircosParams().getCircosTrackByType("COPY-NUMBER");
            if (copyNumberTrack != null) {
                plotCopynumber = true;

                Map<String, String> trackQuery = checkTrackQuery(copyNumberTrack);

                Query copyNumberQuery = new Query(query);
                copyNumberQuery.putAll(trackQuery);

                QueryOptions queryOptions = new QueryOptions(QueryOptions.INCLUDE, "id,sv");

                logger.info("COPY-NUMBER track, query: " + copyNumberQuery.toJson());
                logger.info("COPY-NUMBER track, query options: " + queryOptions.toJson());

                VariantDBIterator iterator = storageManager.iterator(copyNumberQuery, queryOptions, getToken());

                while (iterator.hasNext()) {
                    Variant v = iterator.next();
                    StructuralVariation sv = v.getSv();
                    if (sv != null) {
                        if (sv.getType() == StructuralVariantType.COPY_NUMBER_GAIN) {
                            pw.println("chr" + v.getChromosome() + "\t" + v.getStart() + "\t" + v.getEnd() + "\tNONE\t"
                                    + sv.getCopyNumber() + "\t1");
                        } else if (sv.getType() == StructuralVariantType.COPY_NUMBER_LOSS) {
                            pw.println(v.getChromosome() + "\t" + v.getStart() + "\t" + v.getEnd() + "\tNONE\t"
                                    + "1\t" + sv.getCopyNumber());
                        } else {
                            addWarning("Skipping variant " + v.toString() + ": invalid SV type " + sv.getType() + " for copy-number (CNV)");
                        }
                    } else {
                        addWarning("Skipping variant " + v.toString() + ": SV is empty for copy-number (CNV)");
                    }
                }
            }

            pw.close();
        } catch (Exception e) {
            return false;
        }
        return true;
    }

    /**
     * Create file with INDEL variants.
     *
     * @param query General query
     * @param storageManager    Variant storage manager
     * @return True or false depending on successs
     */
    private boolean indelQuery(Query query, VariantStorageManager storageManager) {
        try {
            indelsFile = getOutDir().resolve("indels.tsv").toFile();
            PrintWriter pw = new PrintWriter(indelsFile);
            pw.println("Chromosome\tchromStart\tchromEnd\ttype\tclassification");

            CircosTrack indelTrack = getCircosParams().getCircosTrackByType("INDEL");
            if (indelTrack != null) {
                plotIndels = true;

                Map<String, String> trackQuery = checkTrackQuery(indelTrack);

                Query indelQuery = new Query(query);
                indelQuery.putAll(trackQuery);

                QueryOptions queryOptions = new QueryOptions(QueryOptions.INCLUDE, "id");

                logger.info("INDEL track, query: " + indelQuery.toJson());
                logger.info("INDEL track, query options: " + queryOptions.toJson());

                VariantDBIterator iterator = storageManager.iterator(indelQuery, queryOptions, getToken());

                while (iterator.hasNext()) {
                    Variant v = iterator.next();
                    switch (v.getType()) {
                        case INSERTION: {
                            pw.println("chr" + v.getChromosome() + "\t" + v.getStart() + "\t" + v.getEnd() + "\tI\tNone");
                            break;
                        }
                        case DELETION: {
                            pw.println("chr" + v.getChromosome() + "\t" + v.getStart() + "\t" + v.getEnd() + "\tD\tNone");
                            break;
                        }
                        case INDEL: {
                            pw.println("chr" + v.getChromosome() + "\t" + v.getStart() + "\t" + v.getEnd() + "\tDI\tNone");
                            break;
                        }
                        default: {
                            // Sanity check
                            addWarning("Skipping variant " + v.toString() + ": invalid type " + v.getType()
                                    + " for INSERTION, DELETION, INDEL");
                            break;
                        }
                    }
                }
            }

            pw.close();
        } catch(Exception e){
            return false;
//            throw new ToolExecutorException(e);
        }
        return true;
    }

    /**
     * Create file with rearrangement variants.
     *
     * @param query General query
     * @param storageManager    Variant storage manager
     * @return True or false depending on successs
     */
    private boolean rearrangementQuery(Query query, VariantStorageManager storageManager) {
        try {
            rearrsFile = getOutDir().resolve("rearrs.tsv").toFile();
            PrintWriter pw = new PrintWriter(rearrsFile);
            pw.println("Chromosome\tchromStart\tchromEnd\tChromosome.1\tchromStart.1\tchromEnd.1\ttype");

            CircosTrack rearrangementTrack = getCircosParams().getCircosTrackByType("REARRANGEMENT");
            if (rearrangementTrack != null) {
                plotRearrangements = true;

                Map<String, String> trackQuery = checkTrackQuery(rearrangementTrack);

                Query rearrangementQuery = new Query(query);
                rearrangementQuery.putAll(trackQuery);

                QueryOptions queryOptions = new QueryOptions(QueryOptions.INCLUDE, "id,sv");

                logger.info("REARRANGEMENT track, query: " + rearrangementQuery.toJson());
                logger.info("REARRANGEMENT track, query options: " + queryOptions.toJson());

                VariantDBIterator iterator = storageManager.iterator(rearrangementQuery, queryOptions, getToken());

                while (iterator.hasNext()) {
                    Variant v = iterator.next();
                    String type = null;
                    switch (v.getType()) {
                        case DELETION: {
                            type = "DEL";
                            break;
                        }
                        case BREAKEND:
                        case TRANSLOCATION: {
                            type = "BND";
                            break;
                        }
                        case DUPLICATION: {
                            type = "DUP";
                            break;
                        }
                        case INVERSION: {
                            type = "INV";
                            break;
                        }
                        default: {
                            // Sanity check
                            addWarning("Skipping variant " + v.toString() + ": invalid type " + v.getType() + " for rearrangement");
                            break;
                        }
                    }

                    if (type != null) {
                        // Check structural variation
                        StructuralVariation sv = v.getSv();
                        if (sv != null) {
                            if (sv.getBreakend() != null) {
                                if (sv.getBreakend().getMate() != null) {
                                    BreakendMate mate = sv.getBreakend().getMate();
                                    pw.println("chr" + v.getChromosome() + "\t" + v.getStart() + "\t" + v.getEnd() + "\tchr"
                                            + mate.getChromosome() + "\t" + mate.getPosition() + "\t" + mate.getPosition() + "\t" + type);
                                } else {
                                    addWarning("Skipping variant " + v.toString() + ": " + v.getType() + ", breakend mate is empty for"
                                            + " rearrangement");
                                }
                            } else {
                                addWarning("Skipping variant " + v.toString() + ": " + v.getType() + ", breakend is empty for"
                                        + " rearrangement");
                            }
                        } else {
                            addWarning("Skipping variant " + v.toString() + ": " + v.getType() + ", SV is empty for rearrangement");
                        }
                    }
                }
            }

            pw.close();
        } catch (Exception e) {
            return false;
        }
        return true;
    }

    private <T> Callable<T> getNamedThread(String name, Callable<T> c) {
        String parentThreadName = Thread.currentThread().getName();
        return () -> {
            Thread.currentThread().setName(parentThreadName + "-" + name);
            return c.call();
        };
    }

    private Map<String, String> checkTrackQuery(CircosTrack track) throws ToolException {
        Map<String, String> query = new HashMap<>();

        if (MapUtils.isNotEmpty(track.getQuery())) {
            query = track.getQuery();
        }

        if ("COPY-NUMBER".equals(track.getType())) {
            query.put("type", "CNV");
        } else if ("INDEL".equals(track.getType())) {
            query.put("type", "INSERTION,DELETION,INDEL");
        } else if ("REARRANGEMENT".equals(track.getType())) {
            query.put("type", "DELETION,TRANSLOCATION,INVERSION,DUPLICATION,BREAKEND");
        } else if ("SNV".equals(track.getType())) {
            query.put("type", "SNV");
        } else {
            throw new ToolException("Unknown Circos track type: '" + track.getType() + "'");
        }

        return query;
    }
}
