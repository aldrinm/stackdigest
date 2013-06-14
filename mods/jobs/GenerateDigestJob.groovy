import org.quartz.JobExecutionException
import org.quartz.JobExecutionContext
import org.quartz.Job

public class GenerateDigestJob implements Job {

    public void execute(JobExecutionContext context) throws JobExecutionException {
      System.err.println( "context.jobDetail.jobDataMap = "+context.jobDetail.jobDataMap);

      def vertx = context.jobDetail.jobDataMap?.vertx 
      generateDigest(vertx)
    }


    def generateDigest(def vertx) {
       vertx.eventBus.send('digestService', [action:'generateDigest']) {reply->

        if (reply?.body?.status == 'ok') {
          //println 'reply = '+reply
        } else {
          println "Error :: digestService.generateDigest"
        }
      };
    }


}
