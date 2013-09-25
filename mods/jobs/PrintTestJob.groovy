import org.quartz.JobExecutionException
import org.quartz.JobExecutionContext
import org.quartz.Job

public class PrintTestJob implements Job {

    public void execute(JobExecutionContext context) throws JobExecutionException {

        println "PRINT TEST JOB.execute"

    }

    
}
