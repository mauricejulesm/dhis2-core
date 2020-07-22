package org.hisp.dhis.programrule.engine;

/*
 * Copyright (c) 2004-2020, University of Oslo
 * All rights reserved.
 *
 * Redistribution and use in source and binary forms, with or without
 * modification, are permitted provided that the following conditions are met:
 * Redistributions of source code must retain the above copyright notice, this
 * list of conditions and the following disclaimer.
 *
 * Redistributions in binary form must reproduce the above copyright notice,
 * this list of conditions and the following disclaimer in the documentation
 * and/or other materials provided with the distribution.
 * Neither the name of the HISP project nor the names of its contributors may
 * be used to endorse or promote products derived from this software without
 * specific prior written permission.
 *
 * THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS" AND
 * ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE IMPLIED
 * WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE ARE
 * DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT OWNER OR CONTRIBUTORS BE LIABLE FOR
 * ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR CONSEQUENTIAL DAMAGES
 * (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES;
 * LOSS OF USE, DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER CAUSED AND ON
 * ANY THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT
 * (INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE OF THIS
 * SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
 */

import static com.google.common.base.Preconditions.checkNotNull;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

import lombok.extern.slf4j.Slf4j;

import org.apache.commons.lang3.ObjectUtils;
import org.apache.commons.lang3.StringUtils;
import org.hisp.dhis.common.ValueType;
import org.hisp.dhis.commons.collection.CachingMap;
import org.hisp.dhis.constant.Constant;
import org.hisp.dhis.constant.ConstantService;
import org.hisp.dhis.dataelement.DataElement;
import org.hisp.dhis.dataelement.DataElementService;
import org.hisp.dhis.eventdatavalue.EventDataValue;
import org.hisp.dhis.i18n.I18nManager;
import org.hisp.dhis.program.Program;
import org.hisp.dhis.program.ProgramInstance;
import org.hisp.dhis.program.ProgramStageInstance;
import org.hisp.dhis.programrule.*;
import org.hisp.dhis.rules.models.*;
import org.hisp.dhis.rules.utils.RuleUtils;
import org.hisp.dhis.trackedentity.TrackedEntityAttribute;
import org.hisp.dhis.trackedentityattributevalue.TrackedEntityAttributeValue;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.google.common.base.Function;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.Lists;

/**
 * Created by zubair@dhis2.org on 19.10.17.
 */
@Slf4j
@Transactional( readOnly = true )
@Service( "org.hisp.dhis.programrule.engine.ProgramRuleEntityMapperService" )
public class DefaultProgramRuleEntityMapperService implements ProgramRuleEntityMapperService
{
    private static final String LOCATION_FEEDBACK = "feedback";

    private static final String LOCATION_INDICATOR = "indicators";

    private final ImmutableMap<ProgramRuleActionType, Function<ProgramRuleAction, RuleAction>> ACTION_MAPPER = new ImmutableMap.Builder<ProgramRuleActionType, Function<ProgramRuleAction, RuleAction>>()
        .put( ProgramRuleActionType.ASSIGN,
            pra -> RuleActionAssign.create( pra.getContent(), pra.getData(),
                getAssignedParameterForAssignAction( pra ) ) )
        .put( ProgramRuleActionType.CREATEEVENT,
            pra -> RuleActionCreateEvent.create( pra.getContent(), pra.getData(), pra.getLocation() ) )
        .put( ProgramRuleActionType.DISPLAYKEYVALUEPAIR, this::getLocationBasedDisplayRuleAction )
        .put( ProgramRuleActionType.DISPLAYTEXT, this::getLocationBasedDisplayRuleAction )
        .put( ProgramRuleActionType.HIDEFIELD,
            pra -> RuleActionHideField.create( pra.getContent(), getAssignedParameter( pra ) ) )
        .put( ProgramRuleActionType.HIDEPROGRAMSTAGE,
            pra -> RuleActionHideProgramStage.create( pra.getProgramStage().getUid() ) )
        .put( ProgramRuleActionType.HIDESECTION,
            pra -> RuleActionHideSection.create( pra.getProgramStageSection().getUid() ) )
        .put( ProgramRuleActionType.SHOWERROR,
            pra -> RuleActionShowError.create( pra.getContent(), pra.getData(), getAssignedParameter( pra ) ) )
        .put( ProgramRuleActionType.SHOWWARNING,
            pra -> RuleActionShowWarning.create( pra.getContent(), pra.getData(), getAssignedParameter( pra ) ) )
        .put( ProgramRuleActionType.SETMANDATORYFIELD,
            pra -> RuleActionSetMandatoryField.create( getAssignedParameter( pra ) ) )
        .put( ProgramRuleActionType.WARNINGONCOMPLETE,
            pra -> RuleActionWarningOnCompletion.create( pra.getContent(), pra.getData(),
                getAssignedParameter( pra ) ) )
        .put( ProgramRuleActionType.ERRORONCOMPLETE,
            pra -> RuleActionErrorOnCompletion.create( pra.getContent(), pra.getData(), getAssignedParameter( pra ) ) )
        .put( ProgramRuleActionType.SENDMESSAGE,
            pra -> RuleActionSendMessage.create( pra.getTemplateUid(), pra.getData() ) )
        .put( ProgramRuleActionType.SCHEDULEMESSAGE,
            pra -> RuleActionScheduleMessage.create( pra.getTemplateUid(), pra.getData() ) )
        .build();

    private final ImmutableMap<ProgramRuleVariableSourceType, Function<ProgramRuleVariable, RuleVariable>> VARIABLE_MAPPER = new ImmutableMap.Builder<ProgramRuleVariableSourceType, Function<ProgramRuleVariable, RuleVariable>>()
        .put( ProgramRuleVariableSourceType.CALCULATED_VALUE,
            prv -> RuleVariableCalculatedValue.create( prv.getName(), "", RuleValueType.TEXT ) )
        .put( ProgramRuleVariableSourceType.TEI_ATTRIBUTE,
            prv -> RuleVariableAttribute.create( prv.getName(), prv.getAttribute().getUid(),
                toMappedValueType( prv ) ) )
        .put( ProgramRuleVariableSourceType.DATAELEMENT_CURRENT_EVENT,
            prv -> RuleVariableCurrentEvent.create( prv.getName(), prv.getDataElement().getUid(),
                toMappedValueType( prv ) ) )
        .put( ProgramRuleVariableSourceType.DATAELEMENT_PREVIOUS_EVENT,
            prv -> RuleVariablePreviousEvent.create( prv.getName(), prv.getDataElement().getUid(),
                toMappedValueType( prv ) ) )
        .put( ProgramRuleVariableSourceType.DATAELEMENT_NEWEST_EVENT_PROGRAM,
            prv -> RuleVariableNewestEvent.create( prv.getName(), prv.getDataElement().getUid(),
                toMappedValueType( prv ) ) )
        .put( ProgramRuleVariableSourceType.DATAELEMENT_NEWEST_EVENT_PROGRAM_STAGE,
            prv -> RuleVariableNewestStageEvent.create( prv.getName(), prv.getDataElement().getUid(),
                prv.getProgramStage().getUid(), toMappedValueType( prv ) ) )
        .build();

    private final ImmutableMap<ProgramRuleVariableSourceType, Function<ProgramRuleVariable, ValueType>> VALUE_TYPE_MAPPER = new ImmutableMap.Builder<ProgramRuleVariableSourceType, Function<ProgramRuleVariable, ValueType>>()
        .put( ProgramRuleVariableSourceType.TEI_ATTRIBUTE, prv -> prv.getAttribute().getValueType() )
        .put( ProgramRuleVariableSourceType.DATAELEMENT_CURRENT_EVENT, prv -> prv.getDataElement().getValueType() )
        .put( ProgramRuleVariableSourceType.DATAELEMENT_PREVIOUS_EVENT, prv -> prv.getDataElement().getValueType() )
        .put( ProgramRuleVariableSourceType.DATAELEMENT_NEWEST_EVENT_PROGRAM,
            prv -> prv.getDataElement().getValueType() )
        .put( ProgramRuleVariableSourceType.DATAELEMENT_NEWEST_EVENT_PROGRAM_STAGE,
            prv -> prv.getDataElement().getValueType() )
        .build();

    private final ImmutableMap<ProgramRuleVariableSourceType, Function<ProgramRuleVariable, String>> DESCRIPTION_MAPPER =
        new ImmutableMap.Builder<ProgramRuleVariableSourceType, Function<ProgramRuleVariable, String>>()
        .put( ProgramRuleVariableSourceType.TEI_ATTRIBUTE, prv ->
        {
            TrackedEntityAttribute attribute = prv.getAttribute();

            return ObjectUtils.firstNonNull( attribute.getDisplayName(), attribute.getDisplayFormName(), attribute.getName() );
        } )
        .put( ProgramRuleVariableSourceType.CALCULATED_VALUE, prv -> ObjectUtils.firstNonNull( prv.getDisplayName(), prv.getName() ) )
        .put( ProgramRuleVariableSourceType.DATAELEMENT_CURRENT_EVENT, this::getDisplayName )
        .put( ProgramRuleVariableSourceType.DATAELEMENT_PREVIOUS_EVENT, this::getDisplayName )
        .put( ProgramRuleVariableSourceType.DATAELEMENT_NEWEST_EVENT_PROGRAM, this::getDisplayName )
        .put( ProgramRuleVariableSourceType.DATAELEMENT_NEWEST_EVENT_PROGRAM_STAGE, this::getDisplayName )
        .build();

    private final CachingMap<String, ValueType> dataElementToValueTypeCache = new CachingMap<>();

    // -------------------------------------------------------------------------
    // Dependencies
    // -------------------------------------------------------------------------

    private final ProgramRuleService programRuleService;

    private final ProgramRuleVariableService programRuleVariableService;

    private final DataElementService dataElementService;

    private final ConstantService constantService;

    private final I18nManager i18nManager;

    public DefaultProgramRuleEntityMapperService( ProgramRuleService programRuleService,
        ProgramRuleVariableService programRuleVariableService, DataElementService dataElementService,
        ConstantService constantService, I18nManager i18nManager )
    {
        checkNotNull( programRuleService );
        checkNotNull( programRuleVariableService );
        checkNotNull( dataElementService );
        checkNotNull( constantService );
        checkNotNull( i18nManager );

        this.programRuleService = programRuleService;
        this.programRuleVariableService = programRuleVariableService;
        this.dataElementService = dataElementService;
        this.constantService = constantService;
        this.i18nManager = i18nManager;
    }

    @Override
    public List<Rule> toMappedProgramRules()
    {
        List<ProgramRule> programRules = programRuleService.getAllProgramRule();

        return toMappedProgramRules( programRules );
    }

    @Override
    public List<Rule> toMappedProgramRules( Program program )
    {
        List<ProgramRule> programRules = programRuleService.getProgramRule( program );

        return toMappedProgramRules( programRules );
    }

    @Override
    public List<Rule> toMappedProgramRules( List<ProgramRule> programRules )
    {
        return programRules.stream().map( this::toRule ).filter( Objects::nonNull ).collect( Collectors.toList() );
    }

    @Override
    public List<RuleVariable> toMappedProgramRuleVariables()
    {
        List<ProgramRuleVariable> programRuleVariables = programRuleVariableService.getAllProgramRuleVariable();

        return toMappedProgramRuleVariables( programRuleVariables );
    }

    @Override
    public List<RuleVariable> toMappedProgramRuleVariables( Program program )
    {
        List<ProgramRuleVariable> programRuleVariables = programRuleVariableService.getProgramRuleVariable( program );

        return toMappedProgramRuleVariables( programRuleVariables );
    }

    @Override
    public List<RuleVariable> toMappedProgramRuleVariables( List<ProgramRuleVariable> programRuleVariables )
    {
        return programRuleVariables
            .stream()
            .filter( Objects::nonNull )
            .map( this::toRuleVariable )
            .filter( Objects::nonNull )
            .collect( Collectors.toList() );
    }

    @Override
    public Rule toMappedProgramRule( ProgramRule programRule )
    {
        return toRule( programRule );
    }

    @Override
    public Map<String, String> getItemStore( List<ProgramRuleVariable> programRuleVariables )
    {
        Map<String, String> itemStore = new HashMap<>();

        // program rule variables
        programRuleVariables.forEach( prv -> itemStore.put( ObjectUtils.firstNonNull( prv.getName(), prv.getDisplayName() ),
            DESCRIPTION_MAPPER.get( prv.getSourceType() ).apply( prv ) ) );

        // constants
        constantService.getAllConstants().forEach( constant -> itemStore.put( constant.getUid(),
            ObjectUtils.firstNonNull( constant.getDisplayName(), constant.getDisplayFormName(), constant.getName() ) ) );

        // program variables

        RuleUtils.ENV_VARIABLES.forEach( var -> itemStore.put( var, i18nManager.getI18n().getString( var ) ) );

        return itemStore;
    }

    @Override
    public RuleEnrollment toMappedRuleEnrollment( ProgramInstance enrollment )
    {
        if ( enrollment == null )
        {
            return null;
        }

        String orgUnit = "";
        String orgUnitCode = "";

        if ( enrollment.getOrganisationUnit() != null )
        {
            orgUnit = enrollment.getOrganisationUnit().getUid();
            orgUnitCode = enrollment.getOrganisationUnit().getCode();
        }

        List<RuleAttributeValue> ruleAttributeValues = Lists.newArrayList();
        if ( enrollment.getEntityInstance() != null )
        {
            ruleAttributeValues = enrollment.getEntityInstance().getTrackedEntityAttributeValues()
                .stream()
                .filter( Objects::nonNull )
                .map( attr -> RuleAttributeValue.create( attr.getAttribute().getUid(),
                    getTrackedEntityAttributeValue( attr ) ) )
                .collect( Collectors.toList() );
        }
        return RuleEnrollment.create( enrollment.getUid(), enrollment.getIncidentDate(), enrollment.getEnrollmentDate(),
            RuleEnrollment.Status.valueOf( enrollment.getStatus().toString() ), orgUnit, orgUnitCode,
            ruleAttributeValues, enrollment.getProgram().getName() );
    }

    @Override
    public List<RuleEvent> toMappedRuleEvents( Set<ProgramStageInstance> programStageInstances,
        Optional<ProgramStageInstance> psiToEvaluate )
    {
        return programStageInstances
            .stream()
            .filter( Objects::nonNull )
            .filter( psi -> !(psiToEvaluate.isPresent() && psi.getUid().equals( psiToEvaluate.get().getUid() )) )
            .map( this::toMappedRuleEvent )
            .collect( Collectors.toList() );
    }

    @Override
    public RuleEvent toMappedRuleEvent( ProgramStageInstance psi )
    {
        if ( psi == null )
        {
            return null;
        }

        String orgUnit = getOrgUnit( psi );
        String orgUnitCode = getOrgUnitCode( psi );

        return RuleEvent.create( psi.getUid(), psi.getProgramStage().getUid(),
            RuleEvent.Status.valueOf( psi.getStatus().toString() ),
            ObjectUtils.defaultIfNull( psi.getExecutionDate(), psi.getDueDate() ), psi.getDueDate(), orgUnit,
            orgUnitCode,
            psi.getEventDataValues()
                .stream()
                .filter( Objects::nonNull )
                .map( dv -> RuleDataValue.create( ObjectUtils.defaultIfNull( psi.getExecutionDate(), psi.getDueDate() ),
                    psi.getProgramStage().getUid(), dv.getDataElement(), getEventDataValue( dv ) ) )
                .collect( Collectors.toList() ),
            psi.getProgramStage().getName() );
    }

    // ---------------------------------------------------------------------
    // Supportive Methods
    // ---------------------------------------------------------------------

    private String getOrgUnit( ProgramStageInstance psi )
    {
        if ( psi.getOrganisationUnit() != null )
        {
            return psi.getOrganisationUnit().getUid();
        }

        return "";
    }

    private String getOrgUnitCode( ProgramStageInstance psi )
    {
        if ( psi.getOrganisationUnit() != null )
        {
            return psi.getOrganisationUnit().getCode();
        }

        return "";
    }

    private Rule toRule( ProgramRule programRule )
    {
        if ( programRule == null )
        {
            return null;
        }

        Set<ProgramRuleAction> programRuleActions = programRule.getProgramRuleActions();

        List<RuleAction> ruleActions;

        Rule rule;
        try
        {
            ruleActions = programRuleActions.stream().map( this::toRuleAction ).collect( Collectors.toList() );

            rule = Rule.create(
                programRule.getProgramStage() != null ? programRule.getProgramStage().getUid() : StringUtils.EMPTY,
                programRule.getPriority(), programRule.getCondition(), ruleActions, programRule.getName() );
        }
        catch ( Exception e )
        {
            log.debug( "Invalid rule action in ProgramRule: " + programRule.getUid() );

            return null;
        }

        return rule;
    }

    private RuleAction toRuleAction( ProgramRuleAction programRuleAction )
    {
        return ACTION_MAPPER
            .getOrDefault( programRuleAction.getProgramRuleActionType(),
                pra -> RuleActionAssign.create( pra.getContent(), pra.getData(), getAssignedParameter( pra ) ) )
            .apply( programRuleAction );
    }

    private RuleVariable toRuleVariable( ProgramRuleVariable programRuleVariable )
    {
        RuleVariable ruleVariable = null;

        try
        {
            if ( VARIABLE_MAPPER.containsKey( programRuleVariable.getSourceType() ) )
            {
                ruleVariable = VARIABLE_MAPPER.get( programRuleVariable.getSourceType() ).apply( programRuleVariable );
            }
        }
        catch ( Exception e )
        {
            log.debug( "Invalid ProgramRuleVariable: " + programRuleVariable.getUid() );
        }

        return ruleVariable;
    }

    private RuleValueType toMappedValueType( ProgramRuleVariable programRuleVariable )
    {
        ValueType valueType = VALUE_TYPE_MAPPER
            .getOrDefault( programRuleVariable.getSourceType(), prv -> ValueType.TEXT ).apply( programRuleVariable );

        if ( valueType.isBoolean() )
        {
            return RuleValueType.BOOLEAN;
        }

        if ( valueType.isText() )
        {
            return RuleValueType.TEXT;
        }

        if ( valueType.isNumeric() )
        {
            return RuleValueType.NUMERIC;
        }

        return RuleValueType.TEXT;
    }

    private String getAssignedParameterForAssignAction( ProgramRuleAction programRuleAction )
    {
        if ( programRuleAction.hasDataElement() )
        {
            return programRuleAction.getDataElement().getUid();
        }

        if ( programRuleAction.hasTrackedEntityAttribute() )
        {
            return programRuleAction.getAttribute().getUid();
        }

        if ( programRuleAction.hasContent() )
        {
            return StringUtils.EMPTY;
        }

        log.warn( String.format( "No location found for ProgramRuleAction: %s in ProgramRule: %s",
            programRuleAction.getProgramRuleActionType(), programRuleAction.getProgramRule().getUid() ) );

        return StringUtils.EMPTY;
    }

    private String getAssignedParameter( ProgramRuleAction programRuleAction )
    {
        if ( programRuleAction.hasDataElement() )
        {
            return programRuleAction.getDataElement().getUid();
        }

        if ( programRuleAction.hasTrackedEntityAttribute() )
        {
            return programRuleAction.getAttribute().getUid();
        }

        if ( programRuleAction.hasContent() )
        {
            return programRuleAction.getContent();
        }

        log.warn( String.format( "No location found for ProgramRuleAction: %s in ProgramRule: %s",
            programRuleAction.getProgramRuleActionType(), programRuleAction.getProgramRule().getUid() ) );

        return StringUtils.EMPTY;
    }

    private RuleAction getLocationBasedDisplayRuleAction( ProgramRuleAction programRuleAction )
    {
        if ( ProgramRuleActionType.DISPLAYTEXT.equals( programRuleAction.getProgramRuleActionType() ) )
        {
            if ( LOCATION_FEEDBACK.equals( programRuleAction.getLocation() ) )
            {
                return RuleActionDisplayText.createForFeedback( programRuleAction.getContent(),
                    programRuleAction.getData() );
            }

            if ( LOCATION_INDICATOR.equals( programRuleAction.getLocation() ) )
            {
                return RuleActionDisplayText.createForIndicators( programRuleAction.getContent(),
                    programRuleAction.getData() );
            }

            return RuleActionDisplayText.createForFeedback( programRuleAction.getContent(),
                programRuleAction.getData() );
        }
        else
        {
            if ( LOCATION_FEEDBACK.equals( programRuleAction.getLocation() ) )
            {
                return RuleActionDisplayKeyValuePair.createForFeedback( programRuleAction.getContent(),
                    programRuleAction.getData() );
            }

            if ( LOCATION_INDICATOR.equals( programRuleAction.getLocation() ) )
            {
                return RuleActionDisplayKeyValuePair.createForIndicators( programRuleAction.getContent(),
                    programRuleAction.getData() );
            }

            return RuleActionDisplayKeyValuePair.createForFeedback( programRuleAction.getContent(),
                programRuleAction.getData() );
        }
    }

    private String getTrackedEntityAttributeValue( TrackedEntityAttributeValue attributeValue )
    {
        ValueType valueType = attributeValue.getAttribute().getValueType();

        if ( valueType.isBoolean() )
        {
            return attributeValue.getValue() != null ? attributeValue.getValue() : "false";
        }

        if ( valueType.isNumeric() )
        {
            return attributeValue.getValue() != null ? attributeValue.getValue() : "0";
        }

        return attributeValue.getValue() != null ? attributeValue.getValue() : "";
    }

    private String getEventDataValue( EventDataValue dataValue )
    {
        ValueType valueType = getValueTypeForDataElement( dataValue.getDataElement() );

        if ( valueType.isBoolean() )
        {
            return dataValue.getValue() != null ? dataValue.getValue() : "false";
        }

        if ( valueType.isNumeric() )
        {
            return dataValue.getValue() != null ? dataValue.getValue() : "0";
        }

        return dataValue.getValue() != null ? dataValue.getValue() : "";
    }

    private ValueType getValueTypeForDataElement( String dataElementUid )
    {
        return dataElementToValueTypeCache.get( dataElementUid, () -> {
            DataElement dataElement = dataElementService.getDataElement( dataElementUid );

            if ( dataElement == null )
            {
                log.error( "DataElement " + dataElementUid + " was not found." );
                throw new IllegalStateException( "Required DataElement(" + dataElementUid + ") was not found." );
            }

            return dataElement.getValueType();
        } );
    }

    private String getDisplayName( ProgramRuleVariable prv )
    {
        DataElement dataElement = prv.getDataElement();

        return ObjectUtils.firstNonNull( dataElement.getDisplayFormName(), dataElement.getFormName(), dataElement.getName() );
    }
}
