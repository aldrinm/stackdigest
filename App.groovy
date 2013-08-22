def webServerConf = [
    port: (container.env['VCAP_APP_PORT'] ?: '8080') as int,
    host: container.env['VCAP_APP_HOST'] ?: '192.168.2.6',
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

container.with {
  deployModule('vertx.mongo-persistor-v1.2', mongoConf, 1) {
}

  deployVerticle("DigestService.groovy") {
      //create output folder if not already present
      new File('room').mkdirs()
  }

    def restConf = [:]

    if (container.env['VCAP_SERVICES']) {
        restConf.clientId = ''
        restConf.clientSecret = ''
        restConf.key = ''
    }
    else {
        restConf.clientId = '1319'
        restConf.clientSecret = 'iG76prVsOW6bLspzL7)kVg(('
        restConf.key = 'ZOrkgbZaY3GOpcG9)TsmBQ(('
    }
    restConf.redirectUrl = "http://${webServerConf.host}:${webServerConf.port}/se-oauth.html".toString()

    //actually only needed by the DigestService and JobService
  deployVerticle("RESTService.groovy", restConf, 1) {
      deployModule("jobs") //dependent on the rest verticle

      //update the sites
      vertx.eventBus.send('restService', [action:'updateStackExchangeSites']) {reply->
          println "...received reply ${reply}"
      }

  }

  deployVerticle("SEService.groovy", restConf)

  def mailConf = [:]
  if (container.env['VCAP_SERVICES']) {

  }
  else {
    mailConf.username = container.env['MAIL_USERNAME']
    mailConf.password= container.env['MAIL_PASSWORD']
    mailConf.fromAddress = "stackdigest@gmail.com"
  }
  deployModule("mail", mailConf)

}


println "App.groovy done with initialization"

