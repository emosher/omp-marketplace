package marketplace.configuration

import grails.core.GrailsApplication
import grails.gorm.transactions.ReadOnly
import grails.gorm.transactions.Transactional
import grails.util.Environment

import org.springframework.beans.factory.NoSuchBeanDefinitionException
import org.springframework.security.web.authentication.session.ConcurrentSessionControlAuthenticationStrategy

import marketplace.Constants
import marketplace.DisableInactiveAccountsJob
import marketplace.ServiceItemService

import ozone.marketplace.enums.MarketplaceApplicationSetting
import org.ozoneplatform.appconfig.server.domain.model.ApplicationConfiguration
import org.ozoneplatform.appconfig.server.service.impl.ApplicationConfigurationServiceImpl

import static ozone.marketplace.enums.MarketplaceApplicationSetting.*


class MarketplaceApplicationConfigurationService extends ApplicationConfigurationServiceImpl {

    GrailsApplication grailsApplication

    ServiceItemService serviceItemService

    def quartzScheduler
    def messageSource

    /** Spring Security bean responsible for handling the max number of sessions. */
    ConcurrentSessionControlAuthenticationStrategy concurrentSessionControlAuthenticationStrategy

    //Save the config object.  The save happens only if its an existing object as inserting a value does not make sense from here

    @Override
    @Transactional
    ApplicationConfiguration saveApplicationConfiguration(ApplicationConfiguration item) {
        item = super.saveApplicationConfiguration(item)

        switch (item.code) {
            case INSIDE_OUTSIDE_BEHAVIOR.code:
                handleInsideOutsideBehaviorChange(item)
                break
            case DISABLE_INACTIVE_ACCOUNTS.code:
                // OP-1318 Disabling inactive user accounts
                handleDisableInactiveAccountsJobChange(item)
                break
        }

        handleSessionControlChange(item)

        item
    }

    private void handleDisableInactiveAccountsJobChange(ApplicationConfiguration configItem) {
        log.info "Doing disableInactiveAccountsJob change"

        def job = new DisableInactiveAccountsJob(getApplicationConfiguration(JOB_DISABLE_ACCOUNTS_INTERVAL).value,
            getApplicationConfiguration(JOB_DISABLE_ACCOUNTS_START).value)

        // Schedule the disable job if turned on, otherwise cancel the job
        if (configItem.value.toBoolean()) {
            job.schedule(quartzScheduler)
        } else {
            job.cancel(quartzScheduler)
        }
    }

    /**
     * helper method for handleSessionControlChange. Ths is the method that actuall updates the
     * property in spring security
     * @param maxSessions The value to be set as the maximumSessions on the spring ConcurrentSessionControlStrategy
     */
    private void updateMaxSessions(int maxSessions) {
        log.debug "Updating max sessions per user to ${maxSessions}"
        log.debug "Session Control Strategy Bean: ${concurrentSessionControlAuthenticationStrategy}"

        if (!concurrentSessionControlAuthenticationStrategy) {
            throw new IllegalStateException("Attempted to update session control configuration when session control bean is not present")
        }

        concurrentSessionControlAuthenticationStrategy.maximumSessions = maxSessions
    }

    /**
     * @return the currently-stored value of the SESSION_CONTROL_ENABLED configuration
     */
    private boolean getSessionControlEnabled() {
        getApplicationConfiguration(SESSION_CONTROL_ENABLED).value.toBoolean()
    }

    /**
     * @return the currently-stored value of the SESSION_CONTROL_MAX_CONCURRENT configuration
     */
    private int getSessionControlMax() {
        getApplicationConfiguration(SESSION_CONTROL_MAX_CONCURRENT).value.toInteger()
    }

    /**
     * Updates the spring security ConcurrentSessionControlStrategy to accept the configured
     * number of concurrent sessions per user.
     * @param item The configuration that was just changed.  If null, this method ensures
     * that the ConcurrentSessionControlStrategy is updated with the current configurations
     * from the database.  If not a SESSION_CONTROL configuration, this method does nothing
     */
    private void handleSessionControlChange(ApplicationConfiguration item) {
        final DISABLED_SETTING = -1 //this value tells spring not to limit sessions

        try {
            //if nothing is passed in, just update spring from the database
            if (!item) {
                updateMaxSessions(getSessionControlEnabled() ? getSessionControlMax() : DISABLED_SETTING)
            }
            //check to see if item is a session control configuration and handle appropriately
            else if (item.code == SESSION_CONTROL_ENABLED.code) {
                updateMaxSessions(item.value.toBoolean() ? getSessionControlMax() : DISABLED_SETTING)
            } else if (item.code == SESSION_CONTROL_MAX_CONCURRENT.code) {
                if (getSessionControlEnabled()) {
                    updateMaxSessions(item.value.toInteger())
                }
            }
            //default - some other type of configuration, do nothing
        }
        catch (NumberFormatException e) {
            log.error "Invalid Session Control configuration: ${e.message}"
        }
    }

    private void handleInsideOutsideBehaviorChange(ApplicationConfiguration configItem) {
        def itemValue = configItem.value
        if (itemValue == Constants.INSIDE_OUTSIDE_ALL_INSIDE || itemValue == Constants.INSIDE_OUTSIDE_ALL_OUTSIDE) {
            def isOutsideValue = (itemValue == Constants.INSIDE_OUTSIDE_ALL_OUTSIDE)
            serviceItemService.makeListingsOutsideOrInside(isOutsideValue)
        }

        // do nothing if transitioning to INSIDE_OUTSIDE_ADMIN_SELECTED
    }

    @ReadOnly
    boolean isFranchiseStore() {
        return is(FRANCHISE_STORE)
    }

    @ReadOnly
    boolean isStoreNameMissing() {
        return (isFranchiseStore() && !this.valueOf(STORE_NAME))
    }

    //This will validate the configuration object
    void validateApplicationConfiguration(def applicationConfiguration) {
        if (!applicationConfiguration)
            return

        def code = applicationConfiguration.code

        if(code in [SESSION_CONTROL_MAX_CONCURRENT, INACTIVITY_THRESHOLD]*.code) {
            def value = applicationConfiguration.value?.isInteger() ? applicationConfiguration.value.toInteger() : -1
            if(value < 1) {
                applicationConfiguration.errors.rejectValue('value', "application.configuration.invalid.number.required.gt.zero")
                return
            }
        }

        if(applicationConfiguration.type == "Integer"){
            def val = applicationConfiguration.value
            if (!val.isInteger() || Integer.valueOf(val) < 0) {
                applicationConfiguration.errors.rejectValue('value', "application.configuration.invalid.integer.gt.zero")
                return
            }
        }

        if (applicationConfiguration.type == "Decimal") {
            def val = applicationConfiguration.value
            if (!val.isNumber() || Double.valueOf(val) < 0) {
                applicationConfiguration.errors.rejectValue('value', "application.configuration.invalid.number.gt.zero")
                return
            }
        }

        if (applicationConfiguration.code == CUSTOM_HEADER_HEIGHT.code || applicationConfiguration.code == CUSTOM_FOOTER_HEIGHT.code) {
            def value = Integer.valueOf(applicationConfiguration.value)
            if (value > 150) {
                applicationConfiguration.errors.rejectValue('value', "application.configuration.custom.headerfooter.height.exceeds.max")
            }
            return
        }

        if (applicationConfiguration.code == SECURITY_LEVEL.code) {
            def value = applicationConfiguration.value
            def validator
            try {
                validator = grailsApplication.mainContext.getBean("securityLevelValidator")
            } catch (NoSuchBeanDefinitionException nbe) {
                log.debug("No security level validation bean found: The security level will not be validated")
                return
            }

            if (validator && !validator.validate(value)) {
                applicationConfiguration.errors.rejectValue('value', "application.configuration.store.security.level.invalid")
                return
            }
        }

        if ((applicationConfiguration.code == STORE_NAME.code) && this.isStoreNameMissing()) {
            applicationConfiguration.errors.rejectValue('value', "application.configuration.store.name.empty")
        }

        super.validate(applicationConfiguration)
    }

    /**
     * Ensures required configs are present in the database.
     */
    @ReadOnly
    void checkThatConfigsExist() {
        log.info "Doing configuration validation"
        MarketplaceApplicationSetting.values().each { setting ->
            def requiredConfig = getApplicationConfiguration(setting)
            if (!requiredConfig) {
                throw new IllegalStateException("The required configuration, ${setting.code}, is missing from the " +
                    "database. Please repair the application configuration table.")
            }
        }
    }

    @Transactional
    initializeConfigDependentServices() {
        //initialize session control
        try {
            //update spring security
            handleSessionControlChange()
        }
        catch (IllegalStateException e) {
            if (Environment.current == Environment.PRODUCTION) {
                log.error "Unable to initialize session management: ${e.message}"
            }
            //this is expected in dev mode, since spring security is not set up
        }

        //initialize handling of inactive accounts
        handleDisableInactiveAccountsJobChange(getApplicationConfiguration(DISABLE_INACTIVE_ACCOUNTS))

    }

    @ReadOnly
    String getApplicationSecurityLevel() {
        return this.valueOf(SECURITY_LEVEL) ?: ""
    }

}
