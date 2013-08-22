def webServerConf = [
    port: (container.env['OPENSHIFT_DIY_PORT'] ?: '8080') as int,
    host: container.env['OPENSHIFT_DIY_IP'] ?: '192.168.2.6',
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

if (container.env['OPENSHIFT_MONGODB_DB_HOST']) {
    mongoConf.host = container.env['OPENSHIFT_MONGODB_DB_HOST']
    mongoConf.port = container.env['OPENSHIFT_MONGODB_DB_PORT'] as int
    mongoConf.db_name = 'stackdigest'
    mongoConf.username = container.env['OPENSHIFT_MONGODB_DB_USERNAME']
    mongoConf.password = container.env['OPENSHIFT_MONGODB_DB_PASSWORD']
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

    if (container.env['OPENSHIFT_APP_NAME']) {
        restConf.clientId = '1265'
        restConf.clientSecret = 'O8lRDMhWx9WvMepepmZm9A(('
        restConf.key = '3UWhhUSOG6WL)cFFLBecpw(('
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

