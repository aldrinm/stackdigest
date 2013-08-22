import org.quartz.JobExecutionException
import org.quartz.JobExecutionContext
import org.quartz.Job

public class FetchUpdatesJob implements Job {

    public void execute(JobExecutionContext context) throws JobExecutionException {
      //System.err.println( "context.jobDetail.jobDataMap = "+context.jobDetail.jobDataMap);

      def vertx = context.jobDetail.jobDataMap?.vertx 
      fetchUpdates(vertx)
    }

    def fetchUpdates(def vertx) {
      //def logger = container.logger
      //logger.debug "Fetching updates.............."
      vertx.eventBus.send('digestService', [action:'fetchUpdates2']) {reply->
        //println "JobService :: reply = ${reply}"

        if (reply?.body?.status == 'ok') {
          //println 'reply = '+reply
        } else {
          println "Error :: digestService.fetchUpdates. reply = $reply"
        }
      };


    }
}
