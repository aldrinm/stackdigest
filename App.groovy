import org.vertx.groovy.core.http.RouteMatcher

def webServerConf = [
    port: (container.env['VCAP_APP_PORT'] ?: '80') as int,
    host: container.env['VCAP_APP_HOST'] ?: 'localhost',
    bridge: true,
  inbound_permitted: [
    [
      address : 'digestService'
    ],
    [
      address : 'mailService'
    ],
    [
      address : 'restService'
    ],
    [
      address : 'seService'
    ],
    [
      address : 'vertx.mongopersistor',
      match : [
        action : 'find',
        collection : 'questions',
      ]
    ],
    [
      address : 'vertx.mongopersistor',
      match : [
        action : 'count',
        collection : 'questions',
      ]
    ],
    [
      address : 'vertx.mongopersistor',
      match : [
        action : 'save',
        collection : 'questions'
      ]
    ],
    [
      address : 'vertx.mongopersistor',
      match : [
        action : 'delete',
        collection : 'questions'
      ]
    ]
  ],

  outbound_permitted: [ [:] ]
]

// Start the web server, with the config we defined above
container.deployModule('vertx.web-server-v1.0', webServerConf)

def mongoConf = [:]

if (container.env['VCAP_SERVICES']) {
    def vcapEnv = new groovy.json.JsonSlurper().parseText(container.env['VCAP_SERVICES'])
println "vcapEnv :: $vcapEnv"
    vcapEnv['mongodb-2.0'].credentials.with {
        mongoConf.host = host[0]
        mongoConf.port = port[0] as int
        mongoConf.db_name = db[0]
        mongoConf.username = username[0]
        mongoConf.password = password[0]
    }
}
else {
  mongoConf.address = 'vertx.mongopersistor'
  mongoConf.host= 'localhost'
  mongoConf.port = 27017
  mongoConf.db_name = 'stackdigest'
}



//println container

container.with {
/*
  deployModule('vertx.mongo-persistor-v1.2', mongoConf, 1) {
    deployVerticle('StaticData.groovy') {
      println "StaticData (dummy done handler. to be fixed in 1.3.1)"  
    }
  }
*/
  deployModule('mod-mongo-persistor', mongoConf, 1) {
    deployVerticle('StaticData.groovy')
  }

  deployVerticle("DigestService.groovy")

    def restConf = [:]

    if (container.env['VCAP_SERVICES']) {

        restConf.clientId = ''
        restConf.clientSecret = ''
        restConf.key = ''
        restConf.redirectUrl = 'http://stackdigest.cloudfoundry.com/se-oauth.html'
    }
    else {
        restConf.clientId = '1319'
        restConf.clientSecret = 'iG76prVsOW6bLspzL7)kVg(('
        restConf.key = 'ZOrkgbZaY3GOpcG9)TsmBQ(('
        restConf.redirectUrl = 'http://localhost/se-oauth.html'
    }

  //actually only needed by the DigestService and JobService
  deployVerticle("RESTService.groovy", restConf, 1) {
      deployModule("jobs") //dependent on the rest verticle
  }

  deployVerticle("SEService.groovy", restConf)

    deployModule("mail")



}


/*
def server = vertx.createHttpServer()
def routeMatcher = new RouteMatcher()

routeMatcher.get("/questions") { req ->
    req.response.end "You requested dogs"
}

server.requestHandler(routeMatcher.asClosure()).listen(80, "localhost")
*/
