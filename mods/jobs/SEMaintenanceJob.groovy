import org.quartz.JobExecutionException
import org.quartz.JobExecutionContext
import org.quartz.Job

public class SEMaintenanceJob implements Job {

    public void execute(JobExecutionContext context) throws JobExecutionException {
      def vertx = context.jobDetail.jobDataMap?.vertx
      updateStackExchangeSites(vertx)
    }

    def updateStackExchangeSites(def vertx) {
       println "[[[[[[[SEMaintenanceJob has started ${new Date()}"

      vertx.eventBus.send('restService', [action:'updateStackExchangeSites']) {reply->
          //println "...received reply ${reply}"
      }
    }
}