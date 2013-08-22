import javax.mail.Authenticator;
import javax.mail.Message;
import javax.mail.MessagingException;
import javax.mail.Multipart;
import javax.mail.PasswordAuthentication;
import javax.mail.Session;
import javax.mail.Transport;
import javax.mail.BodyPart
import javax.mail.Multipart
import javax.mail.internet.AddressException;
import javax.mail.internet.InternetAddress;
import javax.mail.internet.MimeBodyPart;
import javax.mail.internet.MimeMessage;
import javax.mail.internet.MimeMultipart;

import javax.activation.DataSource
import javax.activation.FileDataSource
import javax.activation.DataHandler


vertx.eventBus.registerHandler("mailService") { message ->
    println '=========MAIL SERVICE================' +message?.body
    def body = message?.body

    switch(body?.action) {
    	case 'send':
            body.payload << [from: container.config.fromAddress]
    		sendMail(body.payload)
			message.reply([status: 'pending'])
    		break
	}
}


def sendMail(mailProp) {
    println "mailProp = $mailProp"

	def logger = container.logger		
	  //Authenticator authenticator = new CustomAuthenticator();
  Properties properties = new Properties();
        properties.put("mail.smtp.host", "smtp.gmail.com");
        properties.put("mail.smtp.socketFactory.port", "465");
        properties.put("mail.smtp.auth", "true");
        properties.put("mail.smtp.debug", "true");
        properties.put("mail.smtp.socketFactory.class", "javax.net.ssl.SSLSocketFactory")
        properties.put("mail.smtp.socketFactory.fallback", "false")

     def username = container.config.username
     def password = container.config.password

        Session session = Session.getDefaultInstance(properties, new javax.mail.Authenticator() {
			protected PasswordAuthentication getPasswordAuthentication() {
				return new PasswordAuthentication(username, password);
			}
		  });

	try {
	  	// Create a default MimeMessage object.
	  	new MimeMessage(session).with { message ->
		    // From, Subject and Content
		    from = new InternetAddress(mailProp.from)
		    subject = "Stack digest (${new Date()})"


			BodyPart messageBodyPart = new MimeBodyPart();
	        messageBodyPart.setText("""Hi, 

	        		Your digest for ${new Date()} is attached with this email.

	        		Cheers,
	        		Stackdigest.
	        	""");

        	Multipart multipart = new MimeMultipart();
	        multipart.addBodyPart(messageBodyPart);

	        BodyPart messageBodyPart2 = new MimeBodyPart();
	        DataSource source = new FileDataSource(mailProp.filepath);
	        messageBodyPart2.setFileName("stackdigest-.html")
	        messageBodyPart2.setDataHandler(new DataHandler(source));

	        multipart.addBodyPart(messageBodyPart2);

	        setContent(multipart);

		    // Add recipients
		    addRecipient( Message.RecipientType.TO, new InternetAddress( mailProp.to ) )

		    // Send the message
		    Transport.send( message )

		    //send a clean up message
    	    vertx.eventBus.send('digestService', [action:'cleanup', payload:[filepath: mailProp.filepath]]) {reply->
		       logger.info "Deleting file ${mailProp.filepath} after emailing"
		    }


		    println "Sent successfully"
		  }
	}
	catch( MessagingException mex ) {
    	mex.printStackTrace()
	}

}