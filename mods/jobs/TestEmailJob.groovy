import org.quartz.JobExecutionException
import org.quartz.JobExecutionContext
import org.quartz.Job

public class TestEmailJob implements Job {

    public void execute(JobExecutionContext context) throws JobExecutionException {

      def vertx = context.jobDetail.jobDataMap?.vertx 
      testEmail(vertx)
    }

    def testEmail(def vertx) {
      println "[[[[[[[TestEmailJob has started ${new Date()}"
      vertx.eventBus.send('mailService', [action:'test']) {reply->
        println "TestEmailJob is complete ${new Date()} ]]]]]]]]"
      };


    }
}
