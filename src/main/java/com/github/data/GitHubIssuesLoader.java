package com.github.data;


import com.github.git.PatchStatistics;
import com.github.git.Patches;
import org.apache.commons.io.IOUtils;
import org.apache.spark.api.java.JavaRDD;
import org.apache.spark.api.java.JavaSparkContext;
import org.apache.spark.api.java.function.Function;
import org.apache.spark.mllib.classification.SVMModel;
import org.apache.spark.mllib.classification.SVMWithSGD;
import org.apache.spark.mllib.evaluation.BinaryClassificationMetrics;
import org.apache.spark.mllib.linalg.DenseVector;
import org.apache.spark.mllib.regression.LabeledPoint;
import org.eclipse.egit.github.core.*;
import org.eclipse.egit.github.core.client.*;
import org.eclipse.egit.github.core.service.CommitService;
import org.eclipse.egit.github.core.service.IssueService;
import org.eclipse.egit.github.core.service.RepositoryService;
import scala.Tuple2;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Type;
import java.net.HttpURLConnection;
import java.util.*;

import static org.eclipse.egit.github.core.client.IGitHubConstants.SEGMENT_COMMITS;
import static org.eclipse.egit.github.core.client.IGitHubConstants.SEGMENT_REPOS;

public class GitHubIssuesLoader {


    public List<LabeledPoint> getDataTraining(String owner, String repo) throws Exception {
        GitHubClient client = new GitHubClient();
        client.setOAuth2Token("cb6588ee966e21d166f5e0a87b0380c6b179a941");
        GitHubClient diffClient = new GitHubClient(){
            @Override
            protected HttpURLConnection configureRequest(final HttpURLConnection request){
                HttpURLConnection result = super.configureRequest(request);
                result.setRequestProperty(HEADER_ACCEPT,
                        "application/vnd.github.VERSION.diff");
                return result;
            }

            protected Object getBody(GitHubRequest request, InputStream stream)
                    throws IOException {
                return IOUtils.toString(stream);
            }
        };
        diffClient.setOAuth2Token("8e0d5f998c9f2f690d9e34fbbe91b39039bf3965");
        IssueService srv = new IssueService(client);
        CommitService commitsSrv = new CommitService(client);

        Repository repository = new RepositoryService(client).getRepository(owner, repo);

        List<LabeledPoint> dataTraining = new LinkedList<>();
        dataTraining.addAll(getDataTraining(1.0, srv, diffClient, repository, "Bug"));
        dataTraining.addAll(getDataTraining(0.0, srv, diffClient, repository, "Enhancement"));
        dataTraining.addAll(getDataTraining(0.0, srv, diffClient, repository, "Feature Request"));
        //dataTraining.addAll(getDataTraining(0.0, srv, diffClient, repository, "CleanUp"));
        return dataTraining;
    }


    private List<LabeledPoint> getDataTraining(double rowLabel, IssueService srv,  GitHubClient diffClient,
                                               Repository repository, String label) throws Exception {
        Map<String, String> filters = new HashMap<String, String>();
        filters.put("state", "closed");
        filters.put("labels", label); //,kind/feature,kind/enhancement
        List<LabeledPoint> dataTraining = new LinkedList<LabeledPoint>();
        List<Issue> issues = srv.getIssues(repository, filters);

        System.out.println("data training: "+label+". Total: "+issues.size());

        int index = 0;
        for (Issue issue : issues) {

            int additions = 0;
            int deletions = 0;
            int modifications = 0;

            PageIterator<IssueEvent> events = srv.pageIssueEvents(repository.getOwner().getLogin(),repository.getName(),
                    issue.getNumber());

            while(events.hasNext()){
                Collection<IssueEvent> issueEvents = events.next();
                for(IssueEvent ie: issueEvents){
                    String eventType = ie.getEvent().toLowerCase();

                    if("merged".equals(eventType) || "closed".equals(eventType)) {
                        String commit = ie.getCommitId();
                        if(commit != null) {
                            try {

                                StringBuilder uri = new StringBuilder(SEGMENT_REPOS);
                                uri.append('/').append(repository.getOwner().getLogin());
                                uri.append('/').append(repository.getName());
                                uri.append(SEGMENT_COMMITS);
                                uri.append('/').append(commit);
                                GitHubRequest request = new GitHubRequest();
                                request.setUri(uri);
                                request.setType(RepositoryCommit.class);

                                GitHubResponse response = diffClient.get(request);
                                String patch = response.getBody().toString();

                                if(patch != null ) {

                                    PatchStatistics ps = Patches.getPatchStatistics(patch);
                                    additions += ps.getAdditions();
                                    deletions += ps.getDeletions();
                                    modifications += ps.getModifications();
                                }


                            } catch (RequestException e) {
                                System.out.println("Error on commit " + commit+" at issue "+issue.getNumber());
                            }
                        }
                    }
                }

            }

            if(additions > 0 || deletions > 0) {
                dataTraining.add(new LabeledPoint(rowLabel,
                        new DenseVector(new double[]{additions, deletions, modifications})));
            }
            index++;
            if(index % 10 == 0){
                System.out.println("data training: "+label+". Status :[ "+index+" / "+issues.size()+" ]");
            }
        }
        return dataTraining;
    }

    public static void analysis(){

        JavaSparkContext sc = new JavaSparkContext("local", "commit-classifier");
        JavaRDD<LabeledPoint> data =  sc.textFile("data.csv").map((s)->{
            String[] parts = s.split(" ");
            double additions = Double.parseDouble(parts[1]);
            double deletions = Double.parseDouble(parts[2]);
            double modifications = Double.parseDouble(parts[3]);

            return new LabeledPoint(Double.parseDouble(parts[0]),
                    new DenseVector(new double[]{additions, deletions,
                            modifications}));
        });


        //MLUtils.loadLibSVMFile(sc.sc(), "data.csv").toJavaRDD();
        //JavaRDD<LabeledPoint> data = sc.parallelize(rows);

        JavaRDD<LabeledPoint> training = data.sample(true, 0.6, 11L);
        training.cache();
        JavaRDD<LabeledPoint> test = data.subtract(training);

        // Run training algorithm to build the model.
        int numIterations = 100;

        final SVMModel model = SVMWithSGD.train(training.rdd(), numIterations);

        // Clear the default threshold.
        model.clearThreshold();

        // Compute raw scores on the test set.
        JavaRDD<Tuple2<Object, Object>> scoreAndLabels = test.map(
                new Function<LabeledPoint, Tuple2<Object, Object>>() {
                    public Tuple2<Object, Object> call(LabeledPoint p) {
                        Double score = model.predict(p.features());
                        return new Tuple2<Object, Object>(score, p.label());
                    }
                }
        );

        // Get evaluation metrics.
        BinaryClassificationMetrics metrics =
                new BinaryClassificationMetrics(JavaRDD.toRDD(scoreAndLabels));
        double auROC = metrics.areaUnderROC();

        System.out.println("Area under ROC = " + auROC);

        // Save and load model
        model.save(sc.sc(), "target/tmp/javaSVMWithSGDModel");
        SVMModel sameModel = SVMModel.load(sc.sc(), "target/tmp/javaSVMWithSGDModel");
    }

    public void write(List<LabeledPoint> rows)throws Exception{
        FileWriter fw = new FileWriter(new File("data.csv"));
        for(LabeledPoint lp : rows){
            org.apache.spark.mllib.linalg.Vector v = lp.features();

            int max = v.size();
            String values = "";
            for(int i = 0; i < max; i++) {
                values += v.apply(i);
                if(i+1 < max){
                    values+=" ";
                }
            }
            fw.write(lp.label()+" "+values+"\n");
        }
        fw.close();
    }

    public static void main(String[] args) throws Exception {
        /*GitHubIssuesLoader loader = new GitHubIssuesLoader();
        List<LabeledPoint> rows = loader.getDataTraining("ReactiveX", "RxJava");
        loader.write(rows);*/
        analysis();
    }
}
