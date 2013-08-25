    function savingButton(el, loadingLabel) {
        if (el) {
            $(el).text(loadingLabel).removeClass('success').addClass('secondary').attr('disabled', 'disabled');
        }
    }

    function savedButton(el, defaultLabel) {
        if (el) {
            $(el).text(defaultLabel).removeClass('secondary').addClass('success').removeAttr('disabled');
        }
    }

    $(document).foundation();

    var eb = new vertx.EventBus(window.location.protocol + '//' + window.location.hostname + ':' + window.location.port + '/eventbus');

    var theModel = new SiteViewModel();
      function Site(name, totalFavs, logoUrl, apiSiteParameter) {
        var self = this;
        self.name = name;
        self.totalFavs = totalFavs;
        self.logoUrl = logoUrl;
        self.apiSiteParameter = apiSiteParameter;
      }

      function SiteViewModel() {
          var self = this;
          self.sites = ko.observableArray();

          self.updateEmailAddress = function() {
            savingButton($('button[name="submit-email"]'), 'Saving...');
            eb.send('restService', {action: 'updateEmailAddress',
                        payload: {sessionId: $.cookie('sessionId'), emailAddress: $('input[id="email"]').val()} });
          };

      }

      $(function() {
        var tempCallbackId
          if ($.cookie('sessionId')) {
              //assume the user is logged in. we will check that in a bit
              $('#not-signedin').hide();
              $('#signedin').show();
          }
          else {
              $('#not-signedin').show();
              $('#signedin').hide();
          }


      function updateProfile(profile) {
        $('.profile a').attr('href', 'https://stackexchange.com/users/'+profile.accountId);
        $('.profile span').text(profile.displayName);
        $('.profile img').attr('src', profile.profileImage);
        $('.profile').fadeIn();
        $('input[id="email"]').val(profile.email);
      }

      function sink(message) {
      console.log('==sink==');
      console.log(message);
          switch (message.action) {
              case 'profileUpdate':
                  updateProfile(message.payload);
                  break;

              case 'authSession':
                if (message.payload.status == 'ok') {
                    $('#not-signedin').hide();
                    //register a new listener
                    eb.unregisterHandler('frontend-'+tempCallbackId, sink);
                    eb.registerHandler('frontend-'+$.cookie('sessionId'), sink);
                    sessionStorage.userProfile = JSON.stringify(message.payload.profileDetails);
                    updateProfile(message.payload.profileDetails);
                    fetchAllSites();
                }
                else if (message.payload.status == 'denied') {
                    $('#not-signedin').show();
                    $('#signedin').hide();
                    $.removeCookie('sessionId');
                }
                break;

              case 'updateSites' :
                sessionStorage.userSites = JSON.stringify(message.payload.siteDetails);
                $.each(message.payload.siteDetails,
                        function(ind, val) {
                            theModel.sites.push(new Site(val.name, val.totalFavs, val.logoUrl, val.apiSiteParameter))
                        }
                );
                break;

              case 'updatedEmail':
                    savedButton($('button[name="submit-email"]'), 'Saved');
                    setTimeout(function() {
                        savedButton($('button[name="submit-email"]'), 'Save');
                        }, 2000);
                    break;

              case 'faveCount':
                //console.log(message.payload);
                $('div.siteInfo[name="'+message.payload.apiSiteParameter+'"]').find('div[class="siteInfo-bubble"]').text(message.payload.count+' faves');
                break;
         }
      }

      eb.onopen = function() {
        //eb.registerHandler('frontend', sink);
        initStuff();
      };

      function fetchAllSites() {
        //if not already in storage
        var userSites = sessionStorage.userSites
        if (!userSites) {
            eb.send('restService', {action: 'fetchAllSites', payload: {sessionId: $.cookie('sessionId')} });
        } else {
            userSites = JSON.parse(userSites);
          $.each(userSites,
            function(ind, val) {
                theModel.sites.push(new Site(val.name, val.totalFavs, val.logoUrl, val.apiSiteParameter))
            }
          );
        }
        eb.send('restService', {action:'fetchFaveCounts', payload:{sessionId: $.cookie('sessionId')}});
      }

      function initStuff() {
        ko.applyBindings(theModel);

        //update user logged in stuff before we send out a server auth check request
        var userProfile = sessionStorage.userProfile;
        if (userProfile) {
            userProfile = JSON.parse(userProfile);
            updateProfile(userProfile);
        }

        eb.send('restService', {action: 'oauthConfig'},
            function (oauthConfig) {
                var oUrl = oauthConfig.oAuthUrl
                if (oUrl) {
                  $('a.signin').attr('href', oUrl);
                }
                else {
                  console.error('Failed to retrieve oAuthConfig');
                }
            }
        );

        //create a temporary callback listener till we get a proper session id
        tempCallbackId = Math.random()
        eb.registerHandler('frontend-'+tempCallbackId, sink);
        if ($.cookie('sessionId')) {
          eb.send('restService', {action: 'auth-session', payload: {sessionId: $.cookie('sessionId'),
              tempCallbackId: tempCallbackId}},
              function(message) {
                  //console.log(message);
              }
          );

        }

      };



    });
