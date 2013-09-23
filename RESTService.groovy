import org.vertx.groovy.core.buffer.Buffer
import org.vertx.groovy.core.http.HttpClient
import groovy.json.JsonSlurper
import groovy.json.JsonOutput

import java.util.zip.*
import java.io.*
import java.util.UUID

def config = container.config

vertx.eventBus.registerHandler('restService') { message ->
    def body = message?.body
    //println "body = $body"
    switch(body?.action) {

    	case 'fetchQuestion':
 			def r = fetchQuestion(body.questionId)   		
    		message.reply([status: 'ok', questionDetail: r])
    		break
    	case 'fetchUpdatedAnswers':
 			fetchUpdatedAnswers(body?.payload?.accountId) {
            }
            message.reply([status: 'pending'])
            break
    	case 'fetchAnswers':
 			def r = fetchAnswers(body.questionId, body.nowTimeUnix, body.fromDateUnix)   		
    		message.reply([status: 'ok'])
    		break
    	case 'import':
    		fetchFavorites(body.userid, body.page)   		
    		message.reply([status: 'pending'])	
    		break
        case 'oauthConfig':
            /* message.reply([clientId: config.clientId, secret: config.clientSecret, key: config.key]) */
            message.reply([oAuthUrl: "https://stackexchange.com/oauth?client_id=${config.clientId}&scope=no_expiry&redirect_uri=${config.redirectUrl}".toString()])
            break

        case 'oauth-part2':
            //println body.payload
            def sessionId = UUID.randomUUID().toString()
            getAccessToken(body.payload) {accessToken, errorType, errorMessage->
                if (accessToken) {
                    //generate a session id
                    initUserSession(accessToken, sessionId) {status->
                        vertx.eventBus.send('frontend-'+sessionId,
                                [action: 'updateSession',
                                        payload:[status: status,
                                                sessionId: sessionId
                                        ]
                                ]) {}
                    }
                }
                else {
                    vertx.eventBus.send('frontend-'+sessionId,
                            [action: 'updateSession',
                                    payload:[status: 'error',
                                            errorType: errorType,
                                            errorMessage: errorMessage
                                    ]
                            ]) {}
                }
            }
            //send the session id to the client so that it can start listening
            message.reply([status: 'pending', sessionId: sessionId]);
            break

        case 'syncFavoritesForUI':
            message.reply([status: 'pending'])
            syncFavoritesForUI(body.payload?.accountId) {List siteDetails ->
                vertx.eventBus.send('frontend',
                        [action: 'updateSites',
                                payload:[siteDetails: siteDetails]
                        ]) {}
            }
            break;

        case 'syncFavorites':
            message.reply([status: 'pending'])
            syncFavorites(body.payload?.accountId) {->
                //println "FINALLY done syncing"
            }
            break;

        case 'updateStackExchangeSites':
            updateStackExchangeSites()
            break

        case 'auth-session':
            message.reply([status: 'pending'])
            checkAuth(body?.payload?.sessionId) {result->
                vertx.eventBus.send('frontend-'+body?.payload?.tempCallbackId,
                        [action: 'authSession',
                                payload:[status: result.status, profileDetails: result.payload]
                        ]) {
                    //ignore
                }
            }
            break;

        case 'fetchAllSites':
            fetchAllSites(body?.payload?.sessionId) {List siteDetails ->
                vertx.eventBus.send('frontend-'+body?.payload?.sessionId,
                        [action: 'updateSites',
                                payload:[siteDetails: siteDetails]
                        ]) {}
            }
            break;

        case 'updateEmailAddress':
            if (body?.payload) {
                updateEmailAddress(body.payload.sessionId, body.payload.emailAddress) {status, errorMessage ->
                    vertx.eventBus.send('frontend-'+body.payload?.sessionId,
                            [action: 'updatedEmail',
                                    payload:[status: status,
                                            errorMessage: errorMessage
                                    ]
                            ]) {}
                }
            }
            break;

        case 'fetchFaveCounts':
            if (body.payload) {
                fetchFaveCounts(body.payload.sessionId) {apiSiteParameter, questionCount->
                    vertx.eventBus.send('frontend-'+body.payload?.sessionId,
                            [action: 'faveCount',
                                    payload:[apiSiteParameter: apiSiteParameter,
                                            count: questionCount
                                    ]
                            ]) {}
                }
            }
            break;
    }
}

def fetchFaveCounts(String userSessionId, Closure callback) {
    lookupAccountId(userSessionId) {accountId->
        def sitesQuery = [
                action: 'findone',
                collection: 'users',
                keys: [sites: 1],
                matcher: [accountId: accountId]
        ]
        vertx.eventBus.send('vertx.mongopersistor', sitesQuery) {sitesQueryReply->
            //println "sitesQueryReply.body = ${sitesQueryReply.body}"
            if (sitesQueryReply?.body?.status == 'ok') {
                //def questionsCount = [:]
                sitesQueryReply.body.result?.sites?.each {site->
                    def faveCountsQuery = [
                            action: 'count',
                            collection: 'questions',
                            matcher: [
                                    accountId: accountId,
                                    apiSiteParameter: site.apiSiteParameter
                            ]
                    ]
                    vertx.eventBus.send('vertx.mongopersistor', faveCountsQuery) {faveCountsQueryReply->
                        //println "sitesQueryReply.body = ${sitesQueryReply.body}"
                        if (faveCountsQueryReply?.body?.status == 'ok') {
                            //questionsCount[site.apiSiteParameter] = faveCountsQueryReply?.body?.count
                            //note: this will callback for each site separately
                            callback(site.apiSiteParameter, faveCountsQueryReply?.body?.count)
                        }
                    }
                }
            }
        }
    }
}

def updateEmailAddress(String userSessionId, String emailAddress, Closure callback) {
    lookupAccountId(userSessionId) {accountId->
        def updateUserQuery = [
                action: 'update',
                collection: 'users',
                keys: [_id: 1],
                criteria: [
                        accountId: accountId
                ],
                objNew: [
                    $set: [
                            email: emailAddress
                    ]
                ]
        ]

        vertx.eventBus.send('vertx.mongopersistor', updateUserQuery) {updateUserQueryReply->
            if (updateUserQueryReply?.body?.status == 'ok') {
                callback('ok', '')
            }
            else {
                callback('error', 'Could not save email address')
            }
        }
    }
}

def lookupAccountId(String userSessionId, Closure callback) {
    def userQuery = [
            action:  'findone',
            collection: 'userSession',
            matcher: [sessionId: userSessionId]
    ]
    def accountId
    vertx.eventBus.send('vertx.mongopersistor', userQuery) {mongoreply->
        if (mongoreply?.body?.status == 'ok' && mongoreply?.body?.result?.size()>0) {
            accountId = mongoreply.body.result.accountId
            callback(accountId)
        }
    }
}

def fetchAllSites(String userSessionId, Closure callback) {
    //println "in fetchAllSites with userSessionId = ${userSessionId}"
    lookupAccountId(userSessionId) {accountId->
        lookupAccessToken(accountId) {accessToken->
            fetchAllAssociatedSites(accessToken) {jsonResponse->
                def siteDetails = []
                def countSites = jsonResponse.items.size()
                jsonResponse.items.eachWithIndex {site, i->
                    def siteDetail = [name: site.site_name,
                            url: site.site_url
                    ]

                    //find the corresponding logo and apiSiteParameter
                    def logoUrl, apiSiteParameter
                    def logoQuery = [
                            action: 'findone',
                            collection: 'sites',
                            keys: [logoUrl:1, apiSiteParameter: 1],
                            matcher: [siteUrl: site.site_url]
                    ]
                    vertx.eventBus.send('vertx.mongopersistor', logoQuery) {logoQueryReply->
                        if (logoQueryReply?.body?.status == 'ok' && logoQueryReply?.body?.result?.size()>0) {
                            logoUrl = logoQueryReply.body.result.logoUrl
                            apiSiteParameter = logoQueryReply.body.result.apiSiteParameter
                        }
                        siteDetail.logoUrl = logoUrl
                        siteDetail.apiSiteParameter = apiSiteParameter
                        siteDetails << siteDetail

                        if ((i+1)>=countSites) callback(siteDetails)

                    }
                }
            }
        }

    }
}

def fetchQuestion(int questionId) {
	//http://api.stackexchange.com/2.1/questions/13168779?site=stackoverflow
	HttpClient client = vertx.createHttpClient(host: 'api.stackexchange.com', port: 443, SSL: true, trustAll: true)

	def request = client.get("/2.1/questions/${questionId}?site=stackoverflow&filter=!SkTFqY*mJjzMIRdG.z") { resp ->
  //  println "Got a response: ${resp.statusCode}"
  
  def body = new Buffer('', 'UTF-8')
    resp.dataHandler { buffer ->
    	//println buffer.class
        body << buffer
    }

    resp.endHandler {
		GZIPInputStream  gzip = new GZIPInputStream(new ByteArrayInputStream(body.bytes));
		InputStreamReader reader = new InputStreamReader(gzip);
		BufferedReader inbr = new BufferedReader(reader);

		StringBuilder jsonString = new StringBuilder();
		String r;
		while ((r = inbr.readLine()) != null) {
		    jsonString.append(r)
		}

		/*
		println "================"
		println jsonString.toString()
		println "================"
*/
		def jsonObj = new JsonSlurper().parseText(jsonString.toString())
//		println jsonObj
		vertx.eventBus.send('digestService', [action:'saveQuestionDetails', questionDetails: jsonObj]) {mongoreply->
			//ignore
		}
    }

}


request.end()


}


def fetchAnswers(int questionId, int nowTimeUnix, int fromDateUnix) {
	HttpClient client = vertx.createHttpClient(host: 'api.stackexchange.com', port: 443, SSL: true, trustAll: true)

	def request = client.get("/2.1/questions/${questionId}/answers?site=stackoverflow&filter=!SkTFqY*mJjzMIRdG.z&fromdate=${fromDateUnix}") { resp ->
  //println "Got a response: ${resp.statusCode}"
  
  def body = new Buffer('', 'UTF-8')
    resp.dataHandler { buffer ->
        body << buffer
    }

    resp.endHandler {
		GZIPInputStream  gzip = new GZIPInputStream(new ByteArrayInputStream(body.bytes));
		InputStreamReader reader = new InputStreamReader(gzip);
		BufferedReader inbr = new BufferedReader(reader);

		StringBuilder jsonString = new StringBuilder();
		String r;
		while ((r = inbr.readLine()) != null) {
		    jsonString.append(r)
		}

/*
		println "=======ANSWERS========="
		println jsonString.toString()
		println "================"
*/
		def jsonObj = new JsonSlurper().parseText(jsonString.toString())
		//println jsonObj
		vertx.eventBus.send('digestService', [action:'saveAnswers', questionId:questionId, nowTimeUnix: nowTimeUnix, answers: jsonObj.items]) {mongoreply->
			//ignore
		}
    }

}


request.end()


}

def fetchFavorites(String userid, int page=1) {
//	println "getting PAGE ${page}  "
	//http://api.stackexchange.com/2.1/users/13168779/favorites?site=stackoverflow
	HttpClient client = vertx.createHttpClient(host: 'api.stackexchange.com', port: 443, SSL: true, trustAll: true)
		def request = client.get("/2.1/users/${userid}/favorites?site=stackoverflow&filter=!SkTFqY*mJjzMIRdG.z&page=${page}") { resp ->
		    def body = new Buffer('', 'UTF-8')
		    resp.dataHandler { buffer ->
		        body << buffer
		    }

		    resp.endHandler {
				GZIPInputStream gzip = new GZIPInputStream(new ByteArrayInputStream(body.bytes));
				InputStreamReader reader = new InputStreamReader(gzip);
				BufferedReader inbr = new BufferedReader(reader);

				StringBuilder jsonString = new StringBuilder();
				String r;
				while ((r = inbr.readLine()) != null) {
				    jsonString.append(r)
				}

				def jsonObj = new JsonSlurper().parseText(jsonString.toString())
				//println jsonObj.size()
				vertx.eventBus.send('digestService', [action:'saveFavorites', payload:[favorites: jsonObj, userid: userid, page: page]]) {mongoreply->
					//ignore
				}
		    }
			

		}

		request.end()


}

def initUserSession(String accessToken, String sessionId, Closure callback) {
    fetchUserDetails(accessToken) { displayName, profileImage, accountId ->
        def newUser = [
                action: "update",
                collection: "users",
                keys: [_id:1],
                criteria: [
                        accountId: accountId
                ],
                objNew: [
                        $set: [
                                accountId: accountId,
                                displayName: displayName,
                                profileImage: profileImage,
                                accessToken: accessToken
                        ]
                ],
                upsert: true
        ];
        vertx.eventBus.send('vertx.mongopersistor', newUser) {mongoreply->
        }

        //save session info
        def newSession = [
                action: "update",
                collection: "userSession",
                keys: [_id:1],
                criteria: [
                        accountId: accountId
                ],
                objNew: [
                        $set: [
                                accountId: accountId,
                                sessionId: sessionId
                        ]
                ],
                upsert: true
        ];
        vertx.eventBus.send('vertx.mongopersistor', newSession) {mongoreply->
            callback('ok')
        }

    }
}

def getAccessToken(Map payload, Closure callback) {
    def config = container.config
    //println "code = ${payload.code}"

    HttpClient client = vertx.createHttpClient(host: 'stackexchange.com', port: 443, SSL: true, trustAll: true)
    def request = client.post("/oauth/access_token") { resp ->
        //println "Got a response: ${resp.statusCode}"

        def body = new Buffer('', 'UTF-8')
        resp.dataHandler { buffer ->
            body << buffer
        }

        resp.endHandler {
//            println "{resp.statusCode} = ${resp.statusCode}"
            if (resp.statusCode >= 400) {
                def jsonObj = new JsonSlurper().parseText(body.toString())
                println jsonObj
                callback(null, jsonObj.error?.type, jsonObj.error?.message)
            }
            else {
                //extract the access_token and save it
                def params = extractQueryParams(body.toString())
                //println "params.access_token = ${params.access_token}"

                callback(params.access_token, null, null)
            }
        }
    }

    def b = new StringBuffer("client_id=${config.clientId}&client_secret=${config.clientSecret}&code=${payload.code}&redirect_uri=${config.redirectUrl}")

    request.headers.put("Content-Length", b.length())
    request.headers.put("Content-Type", "application/x-www-form-urlencoded")
    request.write (b.toString())

    request.end()
    client.close()

}

//todo: fetch only one. and the highest used site by this user
private def fetchAnyAssociatedSiteDomain(String accessToken, Closure callback) {
    if (!accessToken) return
    def config = container.config
    def path = "/2.1/me/associated?key=${config.key}&access_token=${accessToken}"
    makeActualRequest(path) { jsonResponse ->
        //println "jsonResponse = $jsonResponse"
        if (!jsonResponse.error_message) {
            def anysiteDomain
            if (jsonResponse?.items?.size() > 0) {
                try {
                    anysiteDomain = new URL(jsonResponse.items[0].site_url).host
                } catch (Exception ex) {
                    //ignore any exception usually MalFormed or NPE
                }
            }
            callback(anysiteDomain)
        }
        else {
            callback(null)
        }
    }
}

private def fetchAllAssociatedSites(def accessToken, Closure callback) {
    if (!accessToken) return
    def config = container.config
    def path = "/2.1/me/associated?key=${config.key}&access_token=${accessToken}"
    makeActualRequest(path) { jsonResponse ->
        //println "jsonResponse = $jsonResponse"
        callback(jsonResponse)
    }
}

private def fetchSiteFavorites(def accessToken, def apiSiteParameter, Integer page=1, Integer pageSize=30, Closure callback) {
    if (!accessToken) return
    def config = container.config
    def path = "/2.1/me/favorites?key=${config.key}&access_token=${accessToken}&site=${apiSiteParameter}&page=${page}&pagesize=${pageSize}"
    makeActualRequest(path) { jsonResponse ->
        //println "favs jsonResponse = $jsonResponse"
        if (!jsonResponse.error_message) {
            callback(jsonResponse)
            if (Boolean.valueOf(jsonResponse?.has_more)) {
                fetchSiteFavorites(accessToken, apiSiteParameter, page+1, pageSize, callback)
            }
        }
        else {
            callback(jsonResponse)
        }
    }
}


private def makeActualRequest(String path, Closure callback) {
    HttpClient client = getHttpClient()
    //println "--- making a request to ${path}"
    def request = client.get(path) { resp ->
        //  println "Got a response: ${resp.statusCode}"

        def body = new Buffer('', 'UTF-8')
        resp.dataHandler { buffer ->
            body << buffer
        }

        resp.endHandler {
            GZIPInputStream  gzip = new GZIPInputStream(new ByteArrayInputStream(body.bytes));
            InputStreamReader reader = new InputStreamReader(gzip);
            BufferedReader inbr = new BufferedReader(reader);

            StringBuilder jsonString = new StringBuilder();
            String r;
            while ((r = inbr.readLine()) != null) {
                jsonString.append(r)
            }

            def jsonObj = new JsonSlurper().parseText(jsonString.toString())

            inbr.close()
            reader.close()
            gzip.close()
            client.close()

            //let's log errors and other such uninteresting stuff
            responseErrorsAndStuff(jsonObj, path)

            callback(jsonObj)
        }
    }


    request.end()
    client.close()

}

private def responseErrorsAndStuff(def jsonResp, String path) {
    if (jsonResp.error_id) {
        println "StackExchange Error - error_id=${jsonResp.error_id}, error_name=${jsonResp.error_name}, " +
                "error_message=${jsonResp.error_message} for api=${path}"
    }
    if (jsonResp.backoff) {
        println "Backoff=${jsonResp.backoff} for api=${path}"
    }
}

private def fetchUserDetails(String accessToken, Closure callback) {
    def config = container.config

    def userData = vertx.sharedData.getMap('userData-'+accessToken)
    //println "************>> userData = $userData"
    if (userData!=null) {
        if (userData['me-response']) {
            def jsonResponse = new JsonSlurper().parseText(userData['me-response'])
            callback(jsonResponse.items[0].display_name, jsonResponse.items[0].profile_image,
                    jsonResponse.items[0].account_id)
        }
        else {
            fetchAnyAssociatedSiteDomain(accessToken) {anysiteDomain ->
                if (anysiteDomain) {
                    def path = "/2.1/me?key=${config.key}&access_token=${accessToken}&site=${anysiteDomain}"
                    makeActualRequest(path) { jsonResponse ->
                        if (jsonResponse?.items?.size() > 0) {
                            userData['me-response'] = JsonOutput.toJson(jsonResponse)
                            callback(jsonResponse.items[0].display_name, jsonResponse.items[0].profile_image,
                                    jsonResponse.items[0].account_id)
                        }
                    }
                }
                else {
                    println "Did not get a domain. Shouldn't happen typically, since a user will have at least one domain"
                }
            }

        }
    }
    else {
        println "Null SharedData. Should not be here !!! "
    }

}

private HttpClient getHttpClient() {
    //http://api.stackexchange.com/2.1/questions/13168779?site=stackoverflow
    return vertx.createHttpClient(host: 'api.stackexchange.com', port: 443, SSL: true, trustAll: true)

}

private Map extractQueryParams(queryString) {
    def paramMap = queryString.split('&').collectEntries { param ->
        param.split('=').collect { URLDecoder.decode(it) }

    }
    return paramMap
}

private def checkAuth(String sessionId, Closure callback) {
    //println "checkAuth called with sessionId = $sessionId"
    if (!sessionId) {
        callback([status: 'denied'])
        return
    }
    def userSessionQuery = [
            action: "find",
            collection: "userSession",
            matcher: [sessionId: sessionId]
        ]
    vertx.eventBus.send('vertx.mongopersistor', userSessionQuery) {mongoreply->
        if (mongoreply?.body?.status == 'ok' && mongoreply?.body?.results?.size()>0) {
            //get the access_token and query the user details
            def accessTokenQuery = [
                    action: "find",
                    collection: "users",
                    matcher: [accountId: mongoreply.body.results[0].accountId]
            ];
            vertx.eventBus.send('vertx.mongopersistor', accessTokenQuery) {accessTokenReply->
                if (accessTokenReply?.body?.status == 'ok' && accessTokenReply?.body?.results?.size() > 0) {
                    def accessToken = accessTokenReply.body.results[0].accessToken
                    fetchUserDetails(accessToken) {displayName, profileImage, accountId ->
                        fetchUserEmail(accountId, accessToken) {String email->
                            callback([status: 'ok',
                                    payload:[displayName: displayName, profileImage: profileImage, accountId: accountId,
                                            email: email]])
                        }
                    }
                }
            }
        }
        else {
            callback([status: 'denied'])
        }
    }

}

def fetchUserEmail(int accountId, String accessToken, Closure callback) {
    //check the cache
    def userData = vertx.sharedData.getMap('userData-'+accessToken)
    if (userData!=null) {
        if (userData['email']) {
            callback(userData['email'])
        }
        else {
            def emailQuery = [
                    action: "find",
                    collection: "users",
                    matcher: [accountId: accountId]
            ]
            vertx.eventBus.send('vertx.mongopersistor', emailQuery) {emailReply->
                if (emailReply?.body?.status == 'ok' && emailReply?.body?.results?.size() > 0) {
                    def email = emailReply.body.results[0].email
                    userData['email'] = email?email:''
                    callback(email)
                }
            }
        }
    }
}

//todo: revisit after writing the actual sync
private def syncFavoritesForUI(int accountId, Closure callback) {
    //first get all the associated sites and save it and then trigger a fetch favs for each site
    def userQuery = [
            action:  'findone',
            collection: 'users',
            matcher: [accountId: accountId]
    ]
    def accessToken
    vertx.eventBus.send('vertx.mongopersistor', userQuery) {mongoreply->
        if (mongoreply?.body?.status == 'ok' && mongoreply?.body?.result?.size()>0) {
//            println "{mongoreply?.body?.result?} = ${mongoreply?.body?.result}"
            accessToken = mongoreply.body.result.accessToken
            fetchAllAssociatedSites(accessToken) {jsonResponse ->
                //println "jsonResponse = $jsonResponse"
                def siteDetails = []
                def countSites = jsonResponse.items.size()
                jsonResponse.items.eachWithIndex {site, i->
                    def siteDetail = [name: site.site_name,
                            url: site.site_url
                    ]

                    //find the corresponding logo
                    def logoUrl
                    def logoQuery = [
                            action: 'findone',
                            collection: 'sites',
                            keys: [logoUrl:1],
                            matcher: [siteUrl: site.site_url]
                    ]
                    vertx.eventBus.send('vertx.mongopersistor', logoQuery) {logoQueryReply->
                        if (logoQueryReply?.body?.status == 'ok' && logoQueryReply?.body?.result?.size()>0) {
                            logoUrl = logoQueryReply.body.result.logoUrl
                        }
                        siteDetail.logoUrl = logoUrl
                        siteDetails << siteDetail
                        //println "i = $i and count = $countSites"

                        if ((i+1)>=countSites) callback(siteDetails)

                    }
                }
            }

        }
        else {
            println "Failed to fetch the user mongoreply?.body?.status=${mongoreply?.body?.status}; " +
                    "mongoreply?.body?.result?.size() = ${mongoreply?.body?.result?.size()}"
        }
    }

}

private def syncFavorites(int accountId, Closure callback) {
    lookupAccessToken(accountId) {accessToken ->
        fetchAllAssociatedSites(accessToken) {allSites ->
            if (!allSites?.error_message) {
                //println "allSites = $allSites"
                def i = 0
                updateSite(accountId, allSites) {site, apiSiteParameter->
                    //println "GOT site = $site"
                    i++
                    if (apiSiteParameter == 'music') { //todo: testing only

                        updateSiteQuestions(accountId, site, apiSiteParameter) {
                            //println "DONE WITH SITE $site"
                        }

                    }
                            /*
                            if (!site?.error_message) {
                                updateSiteQuestions(accountId, accessToken, site, apiSiteParameter) {
                                }
                            }
                            else {
                                println "Error while updating sites : ${sites.error_message}"
                            }
                            */

                }
            }
            else {
                println "Error while fetching all associated sites: ${allSites.error_message}"
            }
        }

    }
}
//todo: delete non-favorited questions
private updateSiteQuestions(int accountId, def site, String apiSiteParameter, Closure callback) {
    lookupAccessToken(accountId) {accessToken ->
        def questionQuery = [
                action: "find",
                collection: "questions",
                keys: [questionId:1],
                matcher: [
                    accountId: accountId,
                    apiSiteParameter: apiSiteParameter
                ]
        ]
        vertx.eventBus.send('vertx.mongopersistor', questionQuery) {questionReply->
            def questionIds = questionReply?.body?.results?.questionId
            //println "questionIds for ${apiSiteParameter} = $questionIds"

            fetchSiteFavorites(accessToken, apiSiteParameter, 1, 30) {favJsonResponse->
//                println "{favJsonResponse?.error_message} = ${favJsonResponse?.error_message}"
                if (favJsonResponse?.error_message) {
                    println "Error from response: "+favJsonResponse?.error_message
                }
                else {
                    favJsonResponse.items.each {q->
                        def newQuestionQuery = [
                                action: "update",
                                collection: "questions",
                                keys: [_id:1],
                                criteria: [
                                    accountId: accountId,
                                    apiSiteParameter: apiSiteParameter,
                                    questionId: q.question_id
                                ],
                                objNew: [
                                    $set : [
                                            accountId: accountId,
                                            apiSiteParameter: apiSiteParameter,
                                            questionId: q.question_id,
                                            question: q
                                            //lastUpdate: (int)(new Date().time/1000)
                                    ]
                                ],
                                upsert: true
                        ]
                        vertx.eventBus.send('vertx.mongopersistor', newQuestionQuery) {newQuestionResult->
                            //ignore
                            //println "newQuestionResult = ${newQuestionResult.body}"
                        }
                    }
                }
            }


        }

    }
}


private lookupApiSiteParameter(String siteUrl, Closure callback) {
    def apiSiteQuery = [
            action: "findone",
            collection: "sites",
            keys: [apiSiteParameter: 1],
            matcher: [siteUrl: siteUrl]
    ]
    vertx.eventBus.send('vertx.mongopersistor', apiSiteQuery) {apiSiteReply->
        //println "apiSiteReply = ${apiSiteReply.body}"
        if (apiSiteReply?.body?.status == 'ok') {
            callback(apiSiteReply.body.result?.apiSiteParameter)
        }
    }
}

private updateSite(int accountId, def siteDetails, Closure callback) {
    def userQuery = [
            action: "findone",
            collection: "users",
            keys: [sites:1],
            matcher: [
                accountId: accountId
            ]
    ]
    vertx.eventBus.send('vertx.mongopersistor', userQuery) {mongoreply->
        def siteUserIds = mongoreply?.body?.result?.sites?.collectMany {
            def t = []
            if (it?.userId) t<< it.userId
            t
        }

        siteDetails.items.each {site ->
            lookupApiSiteParameter(site.site_url) {apiSiteParameter ->
                if (site.user_id in siteUserIds) {
                    //update
                    def updateSiteQuery = [
                            action: "update",
                            collection: "users",
                            keys: [_id:1],
                            criteria: [
                                    accountId: accountId,
                                    'sites.siteUrl': site.site_url
                            ],
                            objNew: [
                                    $set: [
                                            'sites.$.userId': site.user_id,
                                            'sites.$.siteUrl': site.site_url,
                                            'sites.$.apiSiteParameter': apiSiteParameter,
                                            'sites.$.lastUpdate': (int)(new Date().time/1000)
                                    ]
                            ],
                            upsert: true
                    ]
                    vertx.eventBus.send('vertx.mongopersistor', updateSiteQuery) {updateSiteResult->
                        if (updateSiteResult?.body?.status == 'ok') {
                            callback(site, apiSiteParameter)
                        }
                    }
                }
                else {
                    //add new site
                    def newSiteQuery = [
                            action: "update",
                            collection: "users",
                            keys: [_id:1],
                            criteria: [
                                    accountId: accountId
                            ],
                            objNew: [
                                    $push: [
                                            sites: [
                                                userId: site.user_id,
                                                siteUrl: site.site_url,
                                                apiSiteParameter: apiSiteParameter,
                                                lastUpdate: (int)(new Date().time/1000)
                                            ]
                                    ]
                            ],
                            upsert: true
                    ]
                    vertx.eventBus.send('vertx.mongopersistor', newSiteQuery) {newSiteResult ->
                        if (newSiteResult?.body?.status == 'ok') {
                            callback(site, apiSiteParameter)
                        }
                    }
                }
            }
        }

    }


}

private lookupAccessToken(int accountId, Closure callback) {
    def userQuery = [
            action:  'findone',
            collection: 'users',
            matcher: [accountId: accountId]
    ]
    def accessToken
    vertx.eventBus.send('vertx.mongopersistor', userQuery) {mongoreply->
        if (mongoreply?.body?.status == 'ok' && mongoreply?.body?.result?.size()>0) {
            accessToken = mongoreply.body.result.accessToken
            callback(accessToken)
        }
    }
}



def updateStackExchangeSites(Integer page=1, Integer pageSize=30) {
    def path = "/2.1/sites?page=${page}&pagesize=${pageSize}"
    makeActualRequest(path) { jsonResponse ->
        if (jsonResponse.error_message) {
            println "Error while updating SE sites - ${jsonResponse.error_message}"
        }
        else {
            //println "jsonResponse = $jsonResponse"
            jsonResponse?.items?.each {site->
                //println "${site.name} - ${site.api_site_parameter}"
                //save it
                def newSiteQuery = [
                        action: "update",
                        collection: "sites",
                        keys: [_id:1],
                        criteria: [
                                siteUrl: site.site_url
                        ],
                        objNew: [
                                $set: [
                                        name: site.name,
                                        logoUrl: site.icon_url,
                                        apiSiteParameter: site.api_site_parameter,
                                        siteUrl: site.site_url
                                ]
                        ],
                        upsert: true
                ];
                vertx.eventBus.send('vertx.mongopersistor', newSiteQuery) {mongoreply->
                    //ignore
                }
            }

            if (Boolean.valueOf(jsonResponse?.has_more)) {
                updateStackExchangeSites(page+1, pageSize)
            }
        }
    }

}

/* fetch and save the answers for each question that we already have */
private def fetchUpdatedAnswers(int accountId, Closure callback) {
    //for this user, find the questions per site
    //fetch all the questions' answer for each site from the lastUpdated date
    def sitesQuery = [
            action: 'findone',
            collection: 'users',
            keys: [sites: 1],
            matcher: [accountId: accountId]
    ]
    vertx.eventBus.send('vertx.mongopersistor', sitesQuery) {sitesQueryReply->
        //println "sitesQueryReply.body = ${sitesQueryReply.body}"
        if (sitesQueryReply?.body?.status == 'ok') {
            sitesQueryReply.body.result?.sites?.each {site->

                def questionQuery = [
                        action: "find",
                        collection: "questions",
                        keys: [questionId:1, _id:0],
                        matcher: [
                                accountId: accountId,
                                apiSiteParameter: site.apiSiteParameter
                        ]
                ]
                vertx.eventBus.send('vertx.mongopersistor', questionQuery) {questionReply->
                    //println "questionReply.body = ${questionReply.body}"
                    def questionIdList
                    if (questionReply?.body?.status == 'ok') {
                        questionIdList = questionReply.body.results?.collect {it.questionId}
                    }
                    //println "questionIdList.size = ${questionIdList.size()}"

                    if (questionIdList.size() > 0) {
                        deleteAnswers(accountId, questionIdList, site.apiSiteParameter) {
                            //process the list in batches
                            fetchAndSaveUpdatedAnswers(accountId, questionIdList, site.apiSiteParameter, site.lastUpdate) {
                                fetchAndSaveCompleteAnswers(accountId, questionIdList, site.apiSiteParameter, callback)
                            }
                            //also update the question details

                        }
                    }
                }

                //update lastUpdate for the site - TODO
            }
        }
    }
}

private def deleteAnswers(int accountId, List questionIds, String apiSiteParameter, Closure callback) {
//    println "deleting answers for ${apiSiteParameter} and Qs ${questionIds}"
    def deleteQuery = [
            action: 'update',
            collection:  'questions',
            criteria: [
                accountId: accountId,
                apiSiteParameter: apiSiteParameter,
                questionId: ['$in': questionIds]
            ],
            objNew: [
                    $unset: [
                            newAnswers: ''
                    ]
            ],
            multi: true

    ]
    vertx.eventBus.send('vertx.mongopersistor', deleteQuery) {deleteReply->
//        println "deleteReply.body = ${deleteReply.body}"
        callback()
    }
}

private def deleteCompleteAnswers(int accountId, List questionIds, String apiSiteParameter, Closure callback) {
    def deleteQuery = [
            action: 'update',
            collection: 'questions',
            criteria: [
                accountId: accountId,
                apiSiteParameter: apiSiteParameter,
                questionId: ['$in': questionIds]
            ],
            objNew: [
                    $unset: [
                            completeAnswers: ''
                    ]
            ],
            multi: true

    ]
    vertx.eventBus.send('vertx.mongopersistor', deleteQuery) {deleteReply->
//        println "deleteReply.body = ${deleteReply.body}"
        callback()
    }
}


//private fetchAndSaveUpdatedAnswers(List questionIds, Closure callback, int page=1, int pageSize=30) {
private fetchAndSaveUpdatedAnswers(int accountId, List questionIds, String apiSiteParameter, Long lastUpdate, Closure callback) {
    def questionIdsBatch
    if (questionIds.size() <= Constants.MAX_BATCH_SIZE) {
        questionIdsBatch = questionIds.join(';')
    }
    else {
        questionIdsBatch = questionIds.subList(0, Constants.MAX_BATCH_SIZE).join(';')
    }

    //process the questions
    fetchAnswers2(questionIdsBatch, apiSiteParameter, lastUpdate) {jsonResp->
        //save the answers
        if (!jsonResp.error_message) {
            jsonResp.items.each {item->
                saveNewAnswer(accountId, apiSiteParameter, item)
            }
        }
        else {
            println jsonResp
        }
    }

    //todo: lastUpdate should be the date when the question is first saved

    //are there more questions to process...
    if (questionIds.size() > Constants.MAX_BATCH_SIZE) {
        fetchAndSaveUpdatedAnswers(accountId, questionIds.subList(Constants.MAX_BATCH_SIZE,
            questionIds.size()), apiSiteParameter, lastUpdate, callback)
    }
    else {
        callback()
    }

}

private fetchAndSaveCompleteAnswers(int accountId, List questionIds, String apiSiteParameter, Closure callback) {
    def questionIdsBatch
    def questionIdsList = questionIds
    if (questionIds.size() <= Constants.MAX_BATCH_SIZE) {
        questionIdsBatch = questionIds.join(';')
    }
    else {
        questionIdsList = questionIds.subList(0, Constants.MAX_BATCH_SIZE)
        questionIdsBatch = questionIdsList.join(';')
    }

    //process the questions
    fetchAnswers2(questionIdsBatch, apiSiteParameter, 0) {jsonResp->
        //save the answers
        if (!jsonResp.error_message) {
            //delete existing 'complete answers'
            deleteCompleteAnswers(accountId, questionIdsList, apiSiteParameter) {
                jsonResp.items.each {item->
                    saveCompleteAnswer(accountId, apiSiteParameter, item)
                }
            }
        }
        else {
            println jsonResp
        }
    }

    //are there more questions to process...
    if (questionIds.size() > Constants.MAX_BATCH_SIZE) {
        fetchAndSaveCompleteAnswers(accountId, questionIds.subList(Constants.MAX_BATCH_SIZE,
            questionIds.size()), apiSiteParameter, callback)
    }
    else {
        callback()
    }

}

private void saveNewAnswer(int accountId, String apiSiteParameter, def newAnswer) {
    //println "item = $item"
    def answerQuery = [
            action: 'update',
            collection: 'questions',
            keys: [_id: 1],
            criteria: [
                    accountId: accountId,
                    apiSiteParameter: apiSiteParameter,
                    questionId: newAnswer.question_id
            ],
            objNew: [
/*
                    $set: [
                        lastUpdate: (int)(new Date().time/1000)
                    ],
*/
                    $push: [
                        newAnswers: newAnswer,
                    ]
            ]
    ]
    vertx.eventBus.send('vertx.mongopersistor', answerQuery) {answerQueryReply->
    }
}

private void saveCompleteAnswer(int accountId, String apiSiteParameter, def completeAnswer) {
    //println "item = $item"
    def answerQuery = [
            action: 'update',
            collection: 'questions',
            keys: [_id: 1],
            criteria: [
                    accountId: accountId,
                    apiSiteParameter: apiSiteParameter,
                    questionId: completeAnswer.question_id
            ],
            objNew: [
                    $push: [
                        completeAnswers: completeAnswer,
                    ]
            ]
    ]

    vertx.eventBus.send('vertx.mongopersistor', answerQuery) {answerQueryReply->
    }
}



private def fetchAnswers2(String questionIds, String apiSiteParameter, def fromTimeUnix, Integer page=1, Integer pageSize=30, Closure callback) {
    def config = container.config
    if (!fromTimeUnix) fromTimeUnix = 0
    def path = "/2.1/questions/${questionIds}/answers?site=${apiSiteParameter}&filter=!SkTFqY*mJjzMIRdG.z" +
            "&fromdate=${fromTimeUnix}&page=${page}&pagesize=${pageSize}"

    makeActualRequest(path) { jsonResponse ->
        //println "fetchAnswers2 jsonResponse = $jsonResponse"
        if (!jsonResponse.error_message) {
            callback(jsonResponse)
            if (Boolean.valueOf(jsonResponse?.has_more)) {
                fetchAnswers2(questionIds, apiSiteParameter, fromTimeUnix, page+1, pageSize, callback)
            }
        }
        else {
            callback(jsonResponse)
        }
    }
}
