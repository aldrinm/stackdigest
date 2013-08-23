import groovy.text.SimpleTemplateEngine

import org.vertx.groovy.core.buffer.Buffer
import org.vertx.groovy.core.http.HttpClient
import groovy.json.JsonSlurper

import java.util.zip.*
import java.io.*
import java.text.SimpleDateFormat


def logger = container.logger

def saveCallback = {status, payload->
		//vertx.eventBus.send('frontend', [status: 'ok', action: 'updateQuestion', payload:[questionId: 'blahblah'] ]) {reply->
		//logger.debug "body.url = ${body.url}"
	vertx.eventBus.send('frontend', [status: status, action: 'updateQuestion', payload:payload]) {reply->	
		//ignore
	}

}

vertx.eventBus.registerHandler("digestService") { message ->
    logger.debug '*********************************************** digestService ' +message?.body
    def body = message?.body

    switch(body?.action) {
    	case 'save':
 			def questionId = save(body.payload, saveCallback)   		
			message.reply([status: 'pending', payload:[questionId: questionId]])
    		break

    	case 'saveQuestionDetails':
    		saveQuestionDetails(body, saveCallback)
    		message.reply([status: 'ok'])
    		break		

    	case 'fetchUpdates':
    		fetchUpdates()
    		break

    	case 'fetchUpdates2':
    		fetchUpdates2()
    		break

    	case 'generateDigest':
    		generateDigest()
    		break

    	case 'generateDigest2':
    		generateDigest2()
    		break

    	case 'saveAnswers':
 	   		saveAnswers(body, null)
    		break	

		case 'cleanup':
			cleanup(body?.payload)
			break

		case 'import':
			importFavorites(body?.payload)
			message.reply([status: 'pending'])
			break

    	case 'saveFavorites':
    		saveFavorites(body?.payload)
    		message.reply([status: 'pending'])
    		break		

    	case 'generateDigestReport2':
            generateDigestReport2(27899, 'music')
    		message.reply([status: 'pending'])
    		break

    }
}

def generateDigest() {
	println '################### in generateDigest'

	//todo:: make sure it is not already running
	def fetchQuery = [
		action: 'find',
		collection: 'questions',
		matcher: [answersSize: [$gte: 1]]
	]

	vertx.eventBus.send('vertx.mongopersistor', fetchQuery) {reply->
	      if (reply?.body?.status == 'ok') {
	        generateDigestReport(reply.body.results)
    		//message?.reply([body: reply.body])        
	      } else {
	        //console.error('Failed to accept order');
	        println "Some error ${reply?.body}"
	      }
	    };	
}

def generateDigestReport(def questions) {
    def logger = container.logger
	if (questions.size() >0 ) {
		def fle = new File("report.tmpl")
		def nfile = new File("report_${new Date().time}.html")
		def binding = ["questions": questions]
		def engine = new SimpleTemplateEngine()
		def template = engine.createTemplate(fle).make(binding)
		nfile.withPrintWriter{ pwriter ->
		  pwriter.println template.toString()
		}

		println "MAILING the report"
		//mail it 
	    vertx.eventBus.send('mailService', [action:'send', payload:[filepath: nfile.absolutePath, to: 'aldrinm@gmail.com', from: 'stackdigest@gmail.com']]) {reply->
	       logger.debug "Reply from mailService ==> ${reply.body.status}"
	    }
	}
}

def fetchUpdates() {

	//todo:: make sure it is not already running

	def fetchQuery = [
		action: 'find',
		collection: 'questions',
		matcher: [:]
	]

	vertx.eventBus.send('vertx.mongopersistor', fetchQuery) {reply->
	      if (reply?.body?.status == 'ok') {
	        processQuestions(reply.body.results)
    		//message?.reply([body: reply.body])        
	      } else {
	        //console.error('Failed to accept order');
	        println "Error :: while executing fetch query"
	      }
	    };	
}

def fetchUpdates2() {
	def fetchQuery = [
		action: 'find',
		collection: 'users',
        keys: [accountId: 1],
        matcher: [:]
	]

	vertx.eventBus.send('vertx.mongopersistor', fetchQuery) {reply->
	      if (reply?.body?.status == 'ok') {
              reply.body.results.each {user->
                  vertx.eventBus.send('restService', [action: 'fetchUpdatedAnswers',
                          payload:[accountId: user.accountId]]) {
                  }
              }
                  //message?.reply([body: reply.body])
	      } else {
	        //console.error('Failed to accept order');
	        logger.warn "Error :: while executing fetch query ${reply?.body}"
	      }
	    };
}

//todo: questions are not being updated while fetching answer? maybe we should also update the question info
def processQuestions(def questions) {
	def logger = container.logger	
	questions.each {
		if (it.questionId) {
			def nowTimeUnix = (int)(new Date().time/1000) 

			//fetch answers since last update date and save
			vertx.eventBus.send('restService', [action:'fetchAnswers', questionId: it.questionId,
                    nowTimeUnix: nowTimeUnix, fromDateUnix: it.updateTime?:0]) {reply->
				logger.debug "reply.body = ${reply.body}"	
				if (reply.body?.status == 'ok') {
					//println "OK :: fetchAnswers reply from restService"
				}
				else {
					logger.warn "Some error occurred while fetching the answer details"
				}

			}

			//fetch comments since last update date and save
		}
	}
}

def saveQuestionDetails(body, callback) {
	  def logger = container.logger
	def questionDetails = body?.questionDetails
	if (questionDetails) {
		def newQuestion = [			      
				action: "update",
				collection: "questions",
				keys: [_id:1, questionId:1, questionLink: 1, title: 1],
		      	criteria: [
		      		questionId: questionDetails.items[0]?.question_id
		      	],
		      	objNew: [
		      		$set: [
						title: questionDetails.items[0]?.title, 
						questionLink: questionDetails.items[0]?.link,
						questionId: questionDetails.items[0]?.question_id, 
						updateTime: (int)((new Date()-1).time/1000), //initialize to prev day
						question: questionDetails.items[0]  
		      		]
				],
				upsert: true	
			];

		vertx.eventBus.send('vertx.mongopersistor', newQuestion) {mongoreply->
	      if (mongoreply?.body?.status == 'ok') {
	        mongoreply.body.url = mongoreply.body.questionLink
		  	callback('ok', [questionId: mongoreply.body.result.questionId, _id: mongoreply.body.result._id.$oid, title: mongoreply.body.result.title]);
	      } else {
		  	callback('error', [url: questionDetails.items[0]?.link, errorMessage: 'Error when saving the question']);
	        logger.warn "Error while sending a new question to the db"
	      }
	    }		
	}

}

def saveAnswers(body, callback) {
	def logger = container.logger
logger.debug "saveAnswers :: body = $body"
logger.debug "body?.nowTimeUnix ${body?.nowTimeUnix}"
	def questionId = body?.questionId
	def answers = body?.answers
	if (questionId) {
		logger.debug "answers = "+answers
		logger.debug "answers.size() = "+answers.size()
		
		logger.debug "questionId = $questionId"
			def updateAnswers = [			      
				action: "update",
		      	collection: "questions",
		      	criteria: [ 
		      		questionId: questionId
		      	],
		      	objNew: [
					$set: [ answers: answers, answersSize: answers.size(), updateTime: body?.nowTimeUnix]
				]	
			];

			vertx.eventBus.send('vertx.mongopersistor', updateAnswers) {mongoreply->
				println 'updateAnswers mongoreply = '+mongoreply.body
	    	  if (mongoreply?.body?.status == 'ok') {
		  		//callback('ok', [questionId: newQuestion.document.questionId, title: newQuestion.document.title]);
	      		} else {
		  		//callback('error', [url: newQuestion.document.url, errorMessage: 'Error when saving the question']);
	        	println "some error"
	      		}
	      	}


	}

}



def save(body, callback) {    
	if (body?.url) {
		//extract the question id
		//eg. http://stackoverflow.com/questions/13208772/html-change-image-button-and-change-captions
	    List urlTokens = body.url.tokenize('/')
	    def questionId
	    //System.out.println("urlTokens = " + urlTokens);
	    if (urlTokens.size()>=4) {
	             baseUrl = urlTokens[1]
	             questionId = urlTokens[3]
	            //System.out.println("baseUrl = " + baseUrl);
	            //System.out.println("questionId = " + questionId);
	    }

	    questionId = Integer.parseInt(questionId) 

	    if (questionId) {
	    	//check if it already exists
    		def fetchQuery = [
				action: 'count',
				collection: 'questions',
				matcher: [questionId: questionId]
			]

    		vertx.eventBus.send('vertx.mongopersistor', fetchQuery) {reply->
		      if (reply?.body?.status == 'ok') {
	    		//message?.reply([body: reply.body])        
	    		if (reply.body.count>0) {
	    			callback('error', [questionId: questionId, errorMessage: 'Duplicate question'])
	    		}
	    		else {
					//let's try and fetch this question details
					vertx.eventBus.send('restService', [action:'fetchQuestion', questionId: questionId]) {restReply->
						if (restReply.body?.status == 'ok') {
							//ignore	
						}
						else {
							println "Some error occurred while fetching the question details"
						}

					}

	    		}
		      } else {
		        //console.error('Failed to accept order');
		        println "Error occurred while trying to check for duplicate questions"
		      }
		    };	
	


		}	
		else {
			//could not find a question id in the url. Must be an error
			callback('error', [questionId: questionId, errorMessage: 'Cannot interpret your URL'])
		}

		return questionId
	}
	else {
		 //nothing to process
	}

}

def cleanup(Map params) {
	def logger = container.logger
	logger.debug "Deleting file ${params.filepath}"
	new File(params.filepath).delete()
}

def importFavorites(Map params) {
	if (params?.userid) {
		println "========IMPORTING ${params?.userid} "
		
		vertx.eventBus.send('restService', [action:'import', userid: params.userid, page: params.page]) {restReply->
			if (restReply.body?.status == 'pending') {
				//ignore	
			}
			else {
				println "Some error occurred while importing favorites"
			}

		}
		
	}


}

def saveFavorites(payloadJson) {
  def logger = container.logger
	println "=========FAVS ==========="
	//println payloadJson
	println "payloadJson.has_more = ${payloadJson?.favorites?.has_more}"
	//println favorites
	def favorites = payloadJson?.favorites?.items
	favorites.each {f->
		//println "-----------------------------------------------------------------------------------------------------------"
		//println f.question_id
		

		def newQuestion = [			      
				action: "update",
		      	collection: "questions",
		      	criteria: [
		      		questionId: f.question_id
		      	],
		      	objNew: [
		      		$set: [
						title: f.title, 
						questionLink: f.link,
						questionId: f.question_id, 
						updateTime: (int)((new Date()-1).time/1000), //initialize to prev day
						question: f
		      		]
				],
				upsert: true
			];

		vertx.eventBus.send('vertx.mongopersistor', newQuestion) {mongoreply->
	      if (mongoreply?.body?.status == 'ok') {
	        //mongoreply.body.url = newQuestion.document.url
		  	//callback('ok', [questionId: newQuestion.document.questionId, _id: mongoreply.body._id, title: newQuestion.document.title]);

	      } else {
		  	//callback('error', [url: newQuestion.document.url, errorMessage: 'Error when saving the question']);
	        logger.warn "Error while sending a new question to the db"
	      }
	    }
	}

	if (payloadJson?.favorites?.has_more) {
		println "MORE ......."

		vertx.eventBus.send('restService', [action:'import', userid: payloadJson.userid, page:(payloadJson.page + 1)]) {restReply->
					if (restReply.body?.status == 'pending') {
						//ignore	
					}
					else {
						println "Some error occurred while importing favorites"
					}

				}
	}
	else {
		//done. so send a message to the front-end
		//todo: should i be using a publish-subscribe technique
		saveFavoritesCallback('done', [:])
	}




}

def saveFavoritesCallback(String status, Map payload) {
	vertx.eventBus.send('frontend', [status: status, action: 'import', payload:payload]) {reply->	
		//ignore
	}
}


def generateDigest2() {
    def usersQuery = [
            action: "find",
            collection: "users",
            keys: [accountId: 1, _id: 0],
            matcher: [:]
    ]

    vertx.eventBus.send('vertx.mongopersistor', usersQuery) {usersReply->
        if (usersReply?.body?.status == 'ok' && usersReply?.body?.results?.size() > 0) {
            usersReply.body.results.accountId.each {accountId->
                println "************* found account $accountId"
                generateDigest2(accountId)
            }
        }
    }
}

def generateDigest2(int accountId) {
    def sitesQuery = [
            action: "findone",
            collection: "users",
            keys: [sites: 1, email: 1, _id: 0],
            matcher: [
                accountId: accountId
            ]
    ]
    vertx.eventBus.send('vertx.mongopersistor', sitesQuery) {sitesReply->
        //println "sitesReply.body = $sitesReply.body"
        if (sitesReply?.body?.status == 'ok') {
            if (sitesReply.body.result.email) {
                sitesReply.body.result.sites.each {site->
                    //println "site = $site"
                    generateDigestReport2(accountId, sitesReply.body.result.email, site.apiSiteParameter)
                }
            }
        } else {
            println "some error"
        }
    }

}

def generateDigestReport2(int accountId, String emailAddress, def apiSiteParameter) {
    def logger = container.logger

    def newUpdateQuery = [
            action: "find",
            collection: "questions",
            keys: [question: 1, newAnswers: 1, completeAnswers: 1, _id: 0],
            matcher: [
                accountId: accountId,
                apiSiteParameter: apiSiteParameter,
                newAnswers: ['$exists': true]
            ]
    ]

    vertx.eventBus.send('vertx.mongopersistor', newUpdateQuery) {newUpdateReply->
        //println "newUpdateReply.body = ${newUpdateReply.body}"
        if (newUpdateReply?.body?.status == 'ok') {
            def filepath = "room/report_${accountId}_${apiSiteParameter}_${new Date().time}.html"
            produceSiteReport(accountId, apiSiteParameter, newUpdateReply.body.results, filepath)
            println "2. MAILING the report"
            vertx.eventBus.send('mailService', [action:'send', payload:[filepath: nfile.absolutePath,
                    to: emailAddress]]) {reply->
                logger.debug "Reply from mailService ==> ${reply.body.status}"
            }
        } else {
            println "some error"
        }
    }

}

def produceSiteReport(int accountId, String apiSiteParameter, List questions, String filepath) {
    println "producing site report"
    println "questions = $questions"
    if (!questions || questions.size()<1) return

    def fle = new File("report2.tmpl")
    println "here 1"
    def nfile = new File(filepath)
    println "here 2"
    def newAnswersLookupIds = [:]

    questions.each {q->
        def ansIds = q.newAnswers?.collect { it.answer_id }
        newAnswersLookupIds[q.question.question_id] = ansIds
    }

    def binding = [apiSiteParameter: apiSiteParameter.capitalize(),
                    questions: questions,
                    newAnswersLookupIds: newAnswersLookupIds,
                    reportDate: new SimpleDateFormat('dd-MMM-YYYY').format(new Date())
                  ]
    def engine = new SimpleTemplateEngine()
    def template = engine.createTemplate(fle).make(binding)
    nfile.withPrintWriter{ pwriter ->
        pwriter.println template.toString()
    }
}
