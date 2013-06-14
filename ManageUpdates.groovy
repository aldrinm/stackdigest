import org.vertx.groovy.core.Vertx
import org.vertx.groovy.core.buffer.Buffer
import org.vertx.groovy.core.http.HttpClient
import groovy.json.JsonSlurper
import java.util.zip.*

class ManageUpdates {

    private Vertx vertx
    private Map config

    ManageUpdates(Vertx vertx, Map config) {
        this.vertx = vertx
        this.config = config
    }

    public void syncFavorites(int accountId, Closure callback) {
        lookupAccessToken(accountId) {accessToken ->
            if (accessToken) {
                fetchAllAssociatedSites(accessToken) {allSites ->
                    if (!allSites?.error_message) {
                        def updateTime = (int)(new Date().time/1000)
                        updateSites(accountId, allSites, updateTime) {site, apiSiteParameter->
                            println "GOT site = ${site.site_url}"
                            if (apiSiteParameter == 'music') { //todo: testing only
                                updateSiteQuestions(accountId, accessToken, site, apiSiteParameter) {
                                    println "DONE WITH SITE $site"
                                }
                            }
                        }
                    }
                    else {
                        println "Error while fetching all associated sites: ${allSites.error_message}"
                    }
                }

            }
            else {
                println "No access token available for ${accessToken}. This is probably a logic error."
            }

        }
    }

    private void lookupAccessToken(int accountId, Closure callback) {
        def userQuery = [
                action:  'findone',
                collection: 'users',
                matcher: [accountId: accountId]
        ]
        def accessToken

        vertx.eventBus.send('vertx.mongopersistor', userQuery) {userReply->
            if (userReply?.body?.status == 'ok' && userReply?.body?.result?.size()>0) {
                accessToken = userReply.body.result.accessToken
                callback(accessToken)
            }
        }
    }

    private void fetchAllAssociatedSites(def accessToken, Closure callback) {
        if (!accessToken) return
        def path = "/2.1/me/associated?key=${config.key}&access_token=${accessToken}"
        makeActualRequest(path) { jsonResponse ->
            callback(jsonResponse)
        }
    }

    private HttpClient getHttpClient() {
        //http://api.stackexchange.com/2.1/questions/13168779?site=stackoverflow
        return vertx.createHttpClient(host: 'api.stackexchange.com', port: 443, SSL: true, trustAll: true)
    }

    private void makeActualRequest(String path, Closure callback) {
        HttpClient client = getHttpClient()
        println "making a request to ${path}"
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
                callback(jsonObj)
            }
        }


        request.end()
        client.close()

    }

    /**
     * Update the associated sites adding if not already present.
     * @param accountId
     * @param siteDetails
     * @param callback
     */
    private void updateSites(int accountId, def siteDetails, def updateTime, Closure callback) {
        def userQuery = [
                action: "findone",
                collection: "users",
                keys: [sites:1],
                criteria: [
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
                                                'sites.$.lastUpdate': updateTime
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
                                action: "findandmodify",
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
                                                        lastUpdate: updateTime
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

    /**
     * Given the siteurl, find the site parameter name
     * @param siteUrl
     * @param callback
     * @return
     */
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

    private updateSiteQuestions(int accountId, String accessToken, def site, String apiSiteParameter, Closure callback) {
        def questionQuery = [
                action: "find",
                collection: "questions",
                keys: [questionId:1],
                matcher: [
                    accountId: accountId,
                    apiSiteParameter: apiSiteParameter
                ]
        ]
        println "USQ :: site = $site"
        vertx.eventBus.send('vertx.mongopersistor', questionQuery) {questionReply->
            def prevQuestionIds = questionReply?.body?.results?.questionId
            println "questionIds for ${apiSiteParameter} = $prevQuestionIds"

            fetchSiteFavorites(accessToken, apiSiteParameter, 1, 30) {favJsonResponse->
                println "{favJsonResponse?.error_message} = ${favJsonResponse?.error_message}"
                if (favJsonResponse?.error_message) {
                    println "Error from response: "+favJsonResponse?.error_message
                }
                else {
                    favJsonResponse.items.each {q->
                        println "q.question_id = $q.question_id"
                        //remove the Q id from the existing list of questions
                        prevQuestionIds -= q.question_id

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
                            println "newQuestionResult = ${newQuestionResult.body}"
                        }

                    }

                    //any Qs from the store that were not the updated list?
                    println "prevQuestionIds = $prevQuestionIds"
                    deleteQuestions(accountId, prevQuestionIds, apiSiteParameter) { }
                }
            }


        }
    }

    private def fetchSiteFavorites(def accessToken, def apiSiteParameter, Integer page=1, Integer pageSize=30, Closure callback) {
        if (!accessToken) return
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


    private def deleteQuestions(int accountId, List questionIds, String apiSiteParameter, Closure callback) {
        println "deleting questions for ${apiSiteParameter} and Qs ${questionIds}"
        def deleteQuery = [
                action: 'delete',
                collection:  'questions',
                matcher: [
                        accountId: accountId,
                        apiSiteParameter: apiSiteParameter,
                        questionId: ['$in': questionIds]
                ]
        ]
        vertx.eventBus.send('vertx.mongopersistor', deleteQuery) {deleteReply->
            println "deleteReply.body = ${deleteReply.body}"
            callback()
        }
    }


}
