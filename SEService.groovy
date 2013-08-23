vertx.eventBus.registerHandler('seService') { message ->
    def body = message?.body
//    println "body = $body"
    switch(body?.action) {
        case 'syncFavorites':
            message.reply([status: 'pending'])
            new ManageUpdates(vertx, container.config).syncFavorites() {->
                //println "FINALLY done syncing"
            }
            break
    }
}

